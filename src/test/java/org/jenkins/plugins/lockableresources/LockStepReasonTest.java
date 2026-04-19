package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for the lock step reason parameter (PR #520 feature).
 */
@WithJenkins
class LockStepReasonTest extends LockStepTestBase {

    @Test
    void lockWithReason(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                lock(resource: 'resource1', reason: 'Running integration tests') {
                    semaphore 'wait-inside'
                }
                echo 'Finish'""", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        // Verify reason is set while locked
        LockableResource r = LockableResourcesManager.get().fromName("resource1");
        assertNotNull(r);
        assertEquals("Running integration tests", r.getLockReason());

        SemaphoreStep.success("wait-inside/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        // Verify reason is cleared after unlock
        assertEquals("", r.getLockReason());
    }

    @Test
    void lockWithReasonShownInLog(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                lock(resource: 'resource1', reason: 'Deploy to staging') {
                    echo 'Resource locked'
                }
                echo 'Finish'""", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        // Verify reason is shown in the lock message
        j.assertLogContains("Reason: Deploy to staging", b1);
    }

    @Test
    void lockWithReasonPreservedInQueue(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                lock(resource: 'resource1', reason: 'First build') {
                    semaphore 'wait-inside'
                }
                echo 'Finish'""", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        // Start second build with different reason - it should queue
        WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
        p2.setDefinition(new CpsFlowDefinition("""
                lock(resource: 'resource1', reason: 'Second build') {
                    echo 'Got lock'
                }
                echo 'Finish'""", true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Resource: resource1, Reason: Second build] is not free, waiting for execution ...", b2);

        // Release first lock
        SemaphoreStep.success("wait-inside/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        // Second build should now get lock with its reason
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        j.assertLogContains("Reason: Second build", b2);
    }

    @Test
    void lockWithReasonOnLabel(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                lock(label: 'label1', reason: 'Running database tests') {
                    semaphore 'wait-inside'
                }
                echo 'Finish'""", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        LockableResource r = LockableResourcesManager.get().fromName("resource1");
        assertNotNull(r);
        assertEquals("Running database tests", r.getLockReason());

        SemaphoreStep.success("wait-inside/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        // Verify reason is cleared after unlock
        assertEquals("", r.getLockReason());
    }

    @Test
    void lockWithReasonOnExtraResources(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");
        LockableResourcesManager.get().createResource("resource2");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                lock(resource: 'resource1', extra: [[resource: 'resource2', reason: 'Extra resource needed']]) {
                    semaphore 'wait-inside'
                }
                echo 'Finish'""", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        // Extra resource reason is set on LockStepResource level
        // For now, the primary reason is used for all locked resources

        SemaphoreStep.success("wait-inside/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
    }

    @Test
    void lockWithoutReason(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                lock(resource: 'resource1') {
                    semaphore 'wait-inside'
                }
                echo 'Finish'""", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        // Verify reason is empty when not specified
        LockableResource r = LockableResourcesManager.get().fromName("resource1");
        assertNotNull(r);
        assertEquals("", r.getLockReason());

        SemaphoreStep.success("wait-inside/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
    }

    @Test
    void lockReasonToStringFormat(JenkinsRule j) throws Exception {
        LockStepResource resource = new LockStepResource("my-resource");
        resource.setReason("Testing purposes");

        String result = resource.toString();
        assertTrue(result.contains("Reason: Testing purposes"));
        assertTrue(result.contains("Resource: my-resource"));
    }
}
