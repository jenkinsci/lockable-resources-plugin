package org.jenkins.plugins.lockableresources;

import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LockStepSetReservedByInsideLockHonouredTest extends LockStepTestBase {

    private static final Logger LOGGER = Logger.getLogger(LockStepSetReservedByInsideLockHonouredTest.class.getName());

    @Test
    void setReservedByInsideLockHonoured(JenkinsRule j) throws Exception {
        // Use-case is a job keeping the resource reserved so it can use
        // it in other stages and free it later, not all in one closure
        // Variant: directly using the LockableResource object
        LockableResourcesManager lm = LockableResourcesManager.get();
        lm.createResourceWithLabel("resource1", "label1");

        // Can't store in CPS script variable because not serializable:
        String lmget = "org.jenkins.plugins.lockableresources.LockableResourcesManager.get()";
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "setReservedByInsideLockHonoured");
        p.setDefinition(new CpsFlowDefinition(
                "timeout(2) {\n"
                        + "parallel p1: {\n"
                        + "  org.jenkins.plugins.lockableresources.LockableResource lr = null\n"
                        + "  lock(label: 'label1', variable: 'LOCK_NAME') {\n"
                        + "    echo \"VAR IS $env.LOCK_NAME\"\n"
                        + "    lr = "
                        + lmget
                        + ".fromName(env.LOCK_NAME)\n"
                        + "    echo \"Locked resource cause 1-1: ${lr.getLockCause()}\"\n"
                        + "    echo \"Locked resource reservedBy 1-1: ${lr.getReservedBy()}\"\n"
                        + "    lr.setReservedBy('test')\n"
                        + "    echo \"Locked resource cause 1-2: ${lr.getLockCause()}\"\n"
                        + "    echo \"Locked resource reservedBy 1-2: ${lr.getReservedBy()}\"\n"
                        + "    echo \"Unlocking parallel closure 1\"\n"
                        + "    semaphore 'p1-inside'\n"
                        + "  }\n"
                        + "  echo \"Locked resource cause 1-3 (after unlock): ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-3: ${lr.getReservedBy()}\"\n"
                        + "  echo \"Ended locked parallel closure 1 with resource reserved\"\n"
                        + "  echo \"Locked resource cause 1-4: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-4: ${lr.getReservedBy()}\"\n"
                        + "  semaphore 'p1-before-recycle'\n"
                        + "  echo \"Un-reserving Locked resource directly as `lr.recycle()`\"\n"
                        + "  lr.recycle()\n"
                        + "  semaphore 'p1-after-recycle'\n"
                        + "  echo \"Locked resource cause 1-5: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-5: ${lr.getReservedBy()}\"\n"
                        + "  if (lr.getLockCause() == null) {\n"
                        + "    echo \"LRM seems stuck; trying to reserve/unreserve this resource by lock step\"\n"
                        + "    lock(label: 'label1', skipIfLocked: true) { echo \"Secondary lock trick\" }\n"
                        + "  }\n"
                        + "  echo \"Locked resource cause 1-6: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-6: ${lr.getReservedBy()}\"\n"
                        + "},\n"
                        + "p2: {\n"
                        + "  org.jenkins.plugins.lockableresources.LockableResource lr = null\n"
                        + "  echo \"Locked resource cause 2-1: not locked yet\"\n"
                        + "  lock(label: 'label1', variable: 'someVar2') {\n"
                        + "    echo \"VAR2 IS $env.someVar2\"\n"
                        + "    lr = "
                        + lmget
                        + ".fromName(env.someVar2)\n"
                        + "    echo \"Locked resource cause 2-2: ${lr.getLockCause()}\"\n"
                        + "    echo \"Locked resource reservedBy 2-2: ${lr.getReservedBy()}\"\n"
                        + "    semaphore 'p2-holding'\n"
                        + "    echo \"Unlocking parallel closure 2\"\n"
                        + "  }\n"
                        + "  echo \"Locked resource cause 2-3: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 2-3: ${lr.getReservedBy()}\"\n"
                        + "},\n"
                        // Add some pressure to try for race conditions:
                        + "p3: { semaphore 'p3'; lock(label: 'label1') { semaphore 'p4' } },\n"
                        + "p4: { semaphore 'p5'; lock(label: 'label1') { semaphore 'p6' } },\n"
                        + "p5: { semaphore 'p7'; lock(label: 'label1') { semaphore 'p8' } },\n"
                        + "p6: { semaphore 'p9'; lock(label: 'label1') { semaphore 'p10' } },\n"
                        + "p7: { semaphore 'p11'; lock(label: 'label1') { semaphore 'p12' } },\n"
                        + "p8: { semaphore 'p13'; lock(label: 'label1') { semaphore 'p14' } },\n"
                        + "p9: { semaphore 'p15'; lock(label: 'label1') { semaphore 'p16' } }\n"
                        + "\necho \"Survived the test\"\n"
                        + "}", // timeout wrapper
                false));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

        j.waitForMessage("Locked resource cause 1-1", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);

        j.waitForMessage("Locked resource cause 1-2", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);

        SemaphoreStep.success("p1-inside/1", null);

        j.waitForMessage("Locked resource cause 1-3", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);

        j.waitForMessage("Locked resource cause 1-4", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);
        LOGGER.info("GOOD: Did not encounter Bug #1 " + "(parallel p2 gets the lock on a still-reserved resource)!");

        // Ensure p2 is in the waiting queue before p1 calls recycle()
        j.waitForMessage("[Label: label1] is not free, waiting for execution ...", b1);

        SemaphoreStep.success("p1-before-recycle/1", null);
        SemaphoreStep.waitForStart("p2-holding/1", b1);
        // Release pressure stages to start competing
        for (int i = 3; i <= 15; i += 2) {
            SemaphoreStep.success("p" + i + "/1", null);
        }
        SemaphoreStep.success("p1-after-recycle/1", null);

        j.waitForMessage("Locked resource cause 1-5", b1);
        j.assertLogContains("Locked resource cause 2-1", b1);
        j.assertLogContains("Locked resource cause 2-2", b1);

        j.waitForMessage("Locked resource cause 1-6", b1);
        j.assertLogContains("Locked resource cause 2-2", b1);
        j.assertLogContains("Locked resource reservedBy 1-5: null", b1);
        for (String line : new String[] {
            "Locked resource cause 1-5: null", "LRM seems stuck; trying to reserve/unreserve", "Secondary lock trick"
        }) {
            j.assertLogNotContains(line, b1);
        }

        SemaphoreStep.success("p2-holding/1", null);

        j.waitForMessage("Locked resource cause 2-2", b1);
        j.assertLogContains("Locked resource cause 1-5", b1);

        // Release remaining pressure-stage locks as they complete
        for (int i = 4; i <= 16; i += 2) {
            SemaphoreStep.success("p" + i + "/1", null);
        }

        j.assertLogContains(", waiting for execution ...", b1);

        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        j.assertLogContains("Survived the test", b1);
    }
}
