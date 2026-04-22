package org.jenkins.plugins.lockableresources;

import hudson.model.Result;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LockStepTimeoutTest extends LockStepTestBase {

    @BeforeEach
    void setUp() {
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    }

    /**
     * A pipeline that waits for a resource with a short timeout should fail
     * when the resource is never released.
     */
    @Test
    void lockTimeoutExpires(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        // Job A grabs the resource and holds it via semaphore
        WorkflowJob jobA = j.jenkins.createProject(WorkflowJob.class, "jobA");
        jobA.setDefinition(new CpsFlowDefinition("""
                lock('resource1') {
                    semaphore 'hold-lock'
                }
                """, true));
        WorkflowRun runA = jobA.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hold-lock/1", runA);

        // Job B tries to lock with a 3-second timeout
        WorkflowJob jobB = j.jenkins.createProject(WorkflowJob.class, "jobB");
        jobB.setDefinition(new CpsFlowDefinition("""
                lock(resource: 'resource1', timeoutForAllocateResource: 3, timeoutUnit: 'SECONDS') {
                    echo 'Should never reach here'
                }
                """, true));
        WorkflowRun runB = jobB.scheduleBuild2(0).waitForStart();

        // Wait for B to enter the queue
        j.waitForMessage("[Resource: resource1] is not free, waiting for execution ... (timeout: 3 seconds)", runB);

        // B should fail after the timeout
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(runB));
        j.assertLogContains("timed out waiting for resource allocation after 3 seconds", runB);
        j.assertLogNotContains("Should never reach here", runB);

        // Release A so it finishes cleanly
        SemaphoreStep.success("hold-lock/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(runA));
    }

    /**
     * A pipeline with timeoutForAllocateResource=0 (default) waits indefinitely
     * until the resource is freed.
     */
    @Test
    void lockNoTimeoutWaitsIndefinitely(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        // Job A grabs the resource
        WorkflowJob jobA = j.jenkins.createProject(WorkflowJob.class, "jobA");
        jobA.setDefinition(new CpsFlowDefinition("""
                lock('resource1') {
                    semaphore 'hold-lock'
                }
                """, true));
        WorkflowRun runA = jobA.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hold-lock/1", runA);

        // Job B: no timeout → waits until resource is free
        WorkflowJob jobB = j.jenkins.createProject(WorkflowJob.class, "jobB");
        jobB.setDefinition(new CpsFlowDefinition("""
                lock(resource: 'resource1', timeoutForAllocateResource: 0) {
                    echo 'Got the lock'
                }
                echo 'Finish'
                """, true));
        WorkflowRun runB = jobB.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution ...", runB);

        // Release A → B should proceed
        SemaphoreStep.success("hold-lock/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(runA));
        j.assertBuildStatusSuccess(j.waitForCompletion(runB));
        j.assertLogContains("Got the lock", runB);
    }

    /**
     * A pipeline with a timeout that gets the resource in time should succeed.
     */
    @Test
    void lockTimeoutSucceedsWhenResourceFreedInTime(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        // Job A grabs the resource
        WorkflowJob jobA = j.jenkins.createProject(WorkflowJob.class, "jobA");
        jobA.setDefinition(new CpsFlowDefinition("""
                lock('resource1') {
                    semaphore 'hold-lock'
                }
                """, true));
        WorkflowRun runA = jobA.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hold-lock/1", runA);

        // Job B: generous timeout
        WorkflowJob jobB = j.jenkins.createProject(WorkflowJob.class, "jobB");
        jobB.setDefinition(new CpsFlowDefinition("""
                lock(resource: 'resource1', timeoutForAllocateResource: 5, timeoutUnit: 'MINUTES') {
                    echo 'Got the lock in time'
                }
                echo 'Finish'
                """, true));
        WorkflowRun runB = jobB.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Resource: resource1] is not free, waiting for execution ... (timeout: 5 minutes)", runB);

        // Release A quickly → B succeeds within its timeout
        SemaphoreStep.success("hold-lock/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(runA));
        j.assertBuildStatusSuccess(j.waitForCompletion(runB));
        j.assertLogContains("Got the lock in time", runB);
        j.assertLogNotContains("timed out", runB);
    }

    /**
     * Timeout with label-based resource locking.
     */
    @Test
    void lockTimeoutWithLabel(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");

        // Job A grabs all resources with label1
        WorkflowJob jobA = j.jenkins.createProject(WorkflowJob.class, "jobA");
        jobA.setDefinition(new CpsFlowDefinition("""
                lock(label: 'label1', quantity: 1) {
                    semaphore 'hold-lock'
                }
                """, true));
        WorkflowRun runA = jobA.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hold-lock/1", runA);

        // Job B tries label lock with short timeout
        WorkflowJob jobB = j.jenkins.createProject(WorkflowJob.class, "jobB");
        jobB.setDefinition(new CpsFlowDefinition("""
                lock(label: 'label1', quantity: 1, timeoutForAllocateResource: 3, timeoutUnit: 'SECONDS') {
                    echo 'Should not reach here'
                }
                """, true));
        WorkflowRun runB = jobB.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution ... (timeout: 3 seconds)", runB);

        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(runB));
        j.assertLogContains("timed out waiting for resource allocation after 3 seconds", runB);

        SemaphoreStep.success("hold-lock/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(runA));
    }

    /**
     * Multiple queued jobs with different timeouts: the one with shorter timeout
     * fails first, the one with no timeout eventually gets the resource.
     */
    @Test
    void multipleJobsDifferentTimeouts(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        // Job A holds the lock
        WorkflowJob jobA = j.jenkins.createProject(WorkflowJob.class, "jobA");
        jobA.setDefinition(new CpsFlowDefinition("""
                lock('resource1') {
                    semaphore 'hold-lock'
                }
                """, true));
        WorkflowRun runA = jobA.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hold-lock/1", runA);

        // Job B: short timeout → will fail
        WorkflowJob jobB = j.jenkins.createProject(WorkflowJob.class, "jobB");
        jobB.setDefinition(new CpsFlowDefinition("""
                lock(resource: 'resource1', timeoutForAllocateResource: 3, timeoutUnit: 'SECONDS') {
                    echo 'B got the lock'
                }
                """, true));
        WorkflowRun runB = jobB.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Resource: resource1] is not free, waiting for execution ... (timeout: 3 seconds)", runB);

        // Job C: no timeout → will wait and eventually succeed
        WorkflowJob jobC = j.jenkins.createProject(WorkflowJob.class, "jobC");
        jobC.setDefinition(new CpsFlowDefinition("""
                lock('resource1') {
                    echo 'C got the lock'
                }
                echo 'C Finish'
                """, true));
        WorkflowRun runC = jobC.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Resource: resource1] is not free, waiting for execution ...", runC); // no timeout info

        // B fails after timeout
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(runB));
        j.assertLogContains("timed out waiting for resource allocation", runB);

        // Release A → C should get the resource
        SemaphoreStep.success("hold-lock/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(runA));
        j.assertBuildStatusSuccess(j.waitForCompletion(runC));
        j.assertLogContains("C got the lock", runC);
    }
}
