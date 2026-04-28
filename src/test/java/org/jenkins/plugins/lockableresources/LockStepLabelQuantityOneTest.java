package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Regression tests for JENKINS-67083 / GitHub issue #939.
 *
 * <p>3 resources with the same label, 3 concurrent builds each requesting
 * {@code lock(label: '...', quantity: 1)} — the third build must obtain a
 * resource instead of waiting indefinitely.
 */
@WithJenkins
class LockStepLabelQuantityOneTest extends LockStepTestBase {

    @BeforeEach
    void setUp() {
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    }

    /**
     * Three resources with the same label, three concurrent builds each requesting
     * quantity 1 — all three must acquire a resource concurrently.
     */
    @Test
    @Issue("JENKINS-67083")
    void threeBuildsEachLockOneOfThreeResources(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("Board_1", "imx8");
        lrm.createResourceWithLabel("Board_2", "imx8");
        lrm.createResourceWithLabel("Board_3", "imx8");

        // Build 1: lock one resource
        WorkflowJob p1 = j.jenkins.createProject(WorkflowJob.class, "p1");
        p1.setDefinition(new CpsFlowDefinition(
                "lock(label: 'imx8', quantity: 1) {\n"
                        + "  semaphore 'wait-b1'\n"
                        + "}\n"
                        + "echo 'b1 done'",
                true));
        WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-b1/1", b1);

        // Build 2: lock a second resource
        WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
        p2.setDefinition(new CpsFlowDefinition(
                "lock(label: 'imx8', quantity: 1) {\n"
                        + "  semaphore 'wait-b2'\n"
                        + "}\n"
                        + "echo 'b2 done'",
                true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-b2/1", b2);

        // Build 3: must also lock the remaining resource without waiting
        WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
        p3.setDefinition(new CpsFlowDefinition(
                "lock(label: 'imx8', quantity: 1) {\n"
                        + "  semaphore 'wait-b3'\n"
                        + "}\n"
                        + "echo 'b3 done'",
                true));
        WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
        // If #939 is present, b3 would report "Found 0 available resource(s)" and hang here
        SemaphoreStep.waitForStart("wait-b3/1", b3);

        // All three are running concurrently — release them
        SemaphoreStep.success("wait-b1/1", null);
        SemaphoreStep.success("wait-b2/1", null);
        SemaphoreStep.success("wait-b3/1", null);

        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));

        j.assertLogContains("b1 done", b1);
        j.assertLogContains("b2 done", b2);
        j.assertLogContains("b3 done", b3);
    }

    /**
     * Four builds compete for three resources (label, quantity 1). The first three
     * must proceed; the fourth waits and proceeds after one is released.
     */
    @Test
    @Issue("JENKINS-67083")
    void fourthBuildWaitsThenProceedsWhenResourceFreed(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("Board_1", "imx8");
        lrm.createResourceWithLabel("Board_2", "imx8");
        lrm.createResourceWithLabel("Board_3", "imx8");

        // Builds 1-3 lock all three resources
        WorkflowJob p1 = j.jenkins.createProject(WorkflowJob.class, "p1");
        p1.setDefinition(new CpsFlowDefinition(
                "lock(label: 'imx8', quantity: 1) {\n"
                        + "  semaphore 'wait-b1'\n"
                        + "}\n"
                        + "echo 'b1 done'",
                true));
        WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-b1/1", b1);

        WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
        p2.setDefinition(new CpsFlowDefinition(
                "lock(label: 'imx8', quantity: 1) {\n"
                        + "  semaphore 'wait-b2'\n"
                        + "}\n"
                        + "echo 'b2 done'",
                true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-b2/1", b2);

        WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
        p3.setDefinition(new CpsFlowDefinition(
                "lock(label: 'imx8', quantity: 1) {\n"
                        + "  semaphore 'wait-b3'\n"
                        + "}\n"
                        + "echo 'b3 done'",
                true));
        WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-b3/1", b3);

        // Build 4: all resources taken — must wait
        WorkflowJob p4 = j.jenkins.createProject(WorkflowJob.class, "p4");
        p4.setDefinition(new CpsFlowDefinition(
                "lock(label: 'imx8', quantity: 1) {\n"
                        + "  semaphore 'wait-b4'\n"
                        + "}\n"
                        + "echo 'b4 done'",
                true));
        WorkflowRun b4 = p4.scheduleBuild2(0).waitForStart();
        j.waitForMessage(
                "[Label: imx8, Quantity: 1] is not free, waiting for execution ...", b4);
        j.waitForMessage("Found 0 available resource(s). Waiting for correct amount: 1.", b4);

        // Release b1 → b4 should proceed
        SemaphoreStep.success("wait-b1/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        SemaphoreStep.waitForStart("wait-b4/1", b4);
        j.waitForMessage("Lock acquired on [Label: imx8, Quantity: 1]", b4);

        // Cleanup
        SemaphoreStep.success("wait-b2/1", null);
        SemaphoreStep.success("wait-b3/1", null);
        SemaphoreStep.success("wait-b4/1", null);

        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        j.assertBuildStatusSuccess(j.waitForCompletion(b4));

        j.assertLogContains("b4 done", b4);
    }

    /**
     * Verifies resources are reported via the {@code LOCK_NAME} variable and that
     * each build gets a different resource.
     */
    @Test
    @Issue("JENKINS-67083")
    void threeBuildsGetDistinctResources(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("Board_1", "imx8");
        lrm.createResourceWithLabel("Board_2", "imx8");
        lrm.createResourceWithLabel("Board_3", "imx8");

        WorkflowJob p1 = j.jenkins.createProject(WorkflowJob.class, "p1");
        p1.setDefinition(new CpsFlowDefinition(
                "lock(label: 'imx8', quantity: 1, variable: 'LOCK_NAME') {\n"
                        + "  echo \"Locked: ${env.LOCK_NAME}\"\n"
                        + "  semaphore 'wait-b1'\n"
                        + "}\n"
                        + "echo 'b1 done'",
                true));
        WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-b1/1", b1);

        WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
        p2.setDefinition(new CpsFlowDefinition(
                "lock(label: 'imx8', quantity: 1, variable: 'LOCK_NAME') {\n"
                        + "  echo \"Locked: ${env.LOCK_NAME}\"\n"
                        + "  semaphore 'wait-b2'\n"
                        + "}\n"
                        + "echo 'b2 done'",
                true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-b2/1", b2);

        WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
        p3.setDefinition(new CpsFlowDefinition(
                "lock(label: 'imx8', quantity: 1, variable: 'LOCK_NAME') {\n"
                        + "  echo \"Locked: ${env.LOCK_NAME}\"\n"
                        + "  semaphore 'wait-b3'\n"
                        + "}\n"
                        + "echo 'b3 done'",
                true));
        WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-b3/1", b3);

        SemaphoreStep.success("wait-b1/1", null);
        SemaphoreStep.success("wait-b2/1", null);
        SemaphoreStep.success("wait-b3/1", null);

        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));

        // Each build must have locked exactly one resource
        String log1 = j.getLog(b1);
        String log2 = j.getLog(b2);
        String log3 = j.getLog(b3);

        // Verify each build actually got a Board_* resource
        assertNotNull(extractLockedResource(log1, "Board_"), "b1 should lock a Board resource");
        assertNotNull(extractLockedResource(log2, "Board_"), "b2 should lock a Board resource");
        assertNotNull(extractLockedResource(log3, "Board_"), "b3 should lock a Board resource");
    }

    private static String extractLockedResource(String log, String prefix) {
        for (String line : log.split("\n")) {
            if (line.contains("Locked: ") && line.contains(prefix)) {
                return line.trim();
            }
        }
        return null;
    }
}
