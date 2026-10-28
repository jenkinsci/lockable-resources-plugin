package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@Issue("549")
class LockStepReentrantTest extends LockStepTestBase {

    private static final String REENTERED =
            "Resource [A] is already held by an enclosing lock, entering without waiting";

    @Test
    void scriptedNestedLockReenters(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("A", "reentrant-test");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                lock('A') {
                    lock(resource: 'A', reentrant: true) {
                        echo 'inner'
                    }
                }
                echo 'Finish'""", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogContains(REENTERED, b1);
        j.assertLogContains("inner", b1);
        j.assertLogContains("Finish", b1);

        assertFalse(lrm.fromName("A").isLocked());
        assertTrue(lrm.getCurrentQueuedContext().isEmpty());
    }

    @Test
    void declarativeOptionsLockReenters(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("A", "reentrant-test");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                pipeline {
                  agent none
                  options {
                    lock('A')
                  }
                  stages {
                    stage('test') {
                      steps {
                        lock(resource: 'A', reentrant: true) {
                          echo 'inner'
                        }
                      }
                    }
                  }
                }""", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogContains(REENTERED, b1);
        j.assertLogContains("inner", b1);

        assertFalse(lrm.fromName("A").isLocked());
    }

    @Test
    void nestedLockWithoutReentrantStillWaits(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("A", "reentrant-test");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                lock('A') {
                    lock('A') {
                        echo 'inner'
                    }
                }""", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Resource: A] is not free, waiting for execution ...", b1);
        assertEquals(1, lrm.getCurrentQueuedContext().size());

        b1.getExecutor().interrupt();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b1));
        j.assertLogNotContains("inner", b1);
        j.assertLogNotContains(REENTERED, b1);

        assertFalse(lrm.fromName("A").isLocked());
        assertTrue(lrm.getCurrentQueuedContext().isEmpty());
    }

    @Test
    void parallelBranchesWithoutEnclosingLockStillExclude(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("A", "reentrant-test");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                parallel first: {
                    lock(resource: 'A', reentrant: true) {
                        semaphore 'inside'
                    }
                }, second: {
                    lock(resource: 'A', reentrant: true) {
                        semaphore 'inside'
                    }
                }""", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("inside/1", b1);
        j.waitForMessage("[Resource: A] is not free, waiting for execution ...", b1);
        // one branch holds A, the other is queued and has not entered its body
        assertEquals(1, lrm.getCurrentQueuedContext().size());

        SemaphoreStep.success("inside/1", null);
        SemaphoreStep.waitForStart("inside/2", b1);
        assertTrue(lrm.getCurrentQueuedContext().isEmpty());
        SemaphoreStep.success("inside/2", null);

        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogNotContains(REENTERED, b1);
        assertFalse(lrm.fromName("A").isLocked());
    }

    @Test
    void innerBlockEndDoesNotReleaseResource(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("A", "reentrant-test");
        WorkflowJob p1 = j.jenkins.createProject(WorkflowJob.class, "p1");
        p1.setDefinition(new CpsFlowDefinition("""
                lock('A') {
                    lock(resource: 'A', reentrant: true) {
                        echo 'inner'
                    }
                    semaphore 'after-inner'
                }""", true));
        WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("after-inner/1", b1);
        j.assertLogContains("Leaving reentrant lock, resources [A] stay locked by the enclosing lock", b1);

        WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
        p2.setDefinition(new CpsFlowDefinition("""
                lock('A') {
                    echo 'p2 got A'
                }""", true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Resource: A] is not free, waiting for execution ...", b2);

        // the inner block is done, but the outer block of b1 still owns A
        assertEquals(b1, lrm.fromName("A").getBuild());
        assertEquals(1, lrm.getCurrentQueuedContext().size());
        j.assertLogNotContains("p2 got A", b2);

        SemaphoreStep.success("after-inner/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        j.assertLogContains("p2 got A", b2);
        assertFalse(lrm.fromName("A").isLocked());
    }

    @Test
    void partialOverlapDoesNotReenter(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("A", "reentrant-test");
        lrm.createResourceWithLabel("B", "reentrant-test");
        WorkflowJob holder = j.jenkins.createProject(WorkflowJob.class, "holder");
        holder.setDefinition(new CpsFlowDefinition("""
                lock('B') {
                    semaphore 'hold-b'
                }""", true));
        WorkflowRun b0 = holder.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hold-b/1", b0);

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                lock('A') {
                    lock(resource: 'A', extra: [[resource: 'B']], reentrant: true) {
                        echo 'inner'
                    }
                }""", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution ...", b1);
        assertEquals(1, lrm.getCurrentQueuedContext().size());
        j.assertLogNotContains(REENTERED, b1);

        // no partial reentry: freeing B is not enough, the inner lock still waits for A like before
        SemaphoreStep.success("hold-b/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b0));
        assertEquals(1, lrm.getCurrentQueuedContext().size());
        j.assertLogNotContains("inner", b1);

        b1.getExecutor().interrupt();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b1));
        assertFalse(lrm.fromName("A").isLocked());
        assertFalse(lrm.fromName("B").isLocked());
    }
}
