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
class LockStepReserveInsideLockHonouredTest extends LockStepTestBase {

    private static final Logger LOGGER = Logger.getLogger(LockStepReserveInsideLockHonouredTest.class.getName());

    @Test
    void reserveInsideLockHonoured(JenkinsRule j) throws Exception {
        // Use-case is a job keeping the resource reserved so it can use
        // it in other stages and free it later, not all in one closure
        // Variant: using the LockableResourcesManager to manipulate
        // the LockableResource object(s) (with its synchronized code)
        LockableResourcesManager lm = LockableResourcesManager.get();
        lm.createResourceWithLabel("resource1", "label1");

        // Can't store in CPS script variable because not serializable:
        String lmget = "org.jenkins.plugins.lockableresources.LockableResourcesManager.get()";
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "reserveInsideLockHonoured");
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
                        + "    def res = "
                        + lmget
                        + ".reserve([lr], 'test2a')\n"
                        + "    echo \"Locked resource cause 1-2a: ${lr.getLockCause()}\"\n"
                        + "    echo \"Locked resource reservedBy 1-2a: ${lr.getReservedBy()}\"\n"
                        + "    if (!res) {\n"
                        + "        echo \"LockableResourcesManager did not reserve an already locked resource; hack it!\"\n"
                        + "        lr.setReservedBy('test2b')\n"
                        + "        echo \"Locked resource cause 1-2b: ${lr.getLockCause()}\"\n"
                        + "        echo \"Locked resource reservedBy 1-2b: ${lr.getReservedBy()}\"\n"
                        + "    }\n"
                        + "    echo \"Unlocking parallel closure 1\"\n"
                        + "    semaphore 'p1-inside'\n" // Java controls when p1 exits lock
                        + "  }\n"
                        + "  echo \"Locked resource cause 1-3 (after unlock): ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-3: ${lr.getReservedBy()}\"\n"
                        + "  echo \"Ended locked parallel closure 1 with resource reserved\"\n"
                        + "  echo \"Locked resource cause 1-4: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-4: ${lr.getReservedBy()}\"\n"
                        + "  semaphore 'p1-before-reset'\n" // Java asserts p2 hasn't started before reset
                        + "  echo \"Resetting Locked resource via LRM\"\n"
                        + "  "
                        + lmget
                        + ".reset([lr])\n"
                        + "  echo \"Un-reserving Locked resource via LRM\"\n"
                        + "  "
                        + lmget
                        + ".unreserve([lr])\n"
                        + "  semaphore 'p1-after-reset'\n" // Java ensures p2 started before p1 logs 1-5
                        + "  echo \"Locked resource cause 1-5: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-5: ${lr.getReservedBy()}\"\n"
                        + "  if (lr.getLockCause() == null) {\n"
                        + "    echo \"LRM seems stuck; trying to reserve/unreserve this resource by LRM methods\"\n"
                        + "    if ("
                        + lmget
                        + ".reserve([lr], 'unstucker')) {\n"
                        + "        echo \"Secondary lock trick\"\n"
                        + "        "
                        + lmget
                        + ".unreserve([lr])\n"
                        + "    } else { echo \"Could not reserve by LRM methods as 'unstucker'\" }\n"
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
                        + "    echo \"Setting (directly) and dropping (via LRM) a reservation on locked resource\"\n"
                        + "    lr.reserve('test2-1')\n"
                        + "    "
                        + lmget
                        + ".unreserve([lr])\n"
                        + "    semaphore 'p2-holding'\n" // Java controls when p2 sets test2-2 and exits
                        + "    echo \"Setting (directly) a reservation on locked resource\"\n"
                        + "    lr.reserve('test2-2')\n"
                        + "    echo \"Unlocking parallel closure 2\"\n"
                        + "  }\n"
                        + "  echo \"Locked resource cause 2-3: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 2-3: ${lr.getReservedBy()}\"\n"
                        + "  echo \"Recycling (via LRM) the reserved not-locked resource\"\n"
                        + "  semaphore 'p2-before-recycle'\n" // Java asserts p3 hasn't got the lock before recycle
                        + "  "
                        + lmget
                        + ".recycle([lr])\n"
                        + "  semaphore 'p2-after-recycle'\n" // Java ensures p3 started before p2 logs 2-4
                        + "  echo \"Locked resource cause 2-4: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 2-4: ${lr.getReservedBy()}\"\n"
                        + "},\n"
                        // Test that reserve/unreserve in p2 did not "allow" p3 to kidnap the lock:
                        + "p3: {\n"
                        + "  org.jenkins.plugins.lockableresources.LockableResource lr = null\n"
                        + "  echo \"Locked resource cause 3-1: not locked yet\"\n"
                        + "  semaphore 'p3-wait'\n" // Java controls when p3 enters the queue
                        + "  lock(label: 'label1', variable: 'someVar3') {\n"
                        + "    echo \"VAR3 IS $env.someVar3\"\n"
                        + "    lr = "
                        + lmget
                        + ".fromName(env.someVar3)\n"
                        + "    echo \"Locked resource cause 3-2: ${lr.getLockCause()}\"\n"
                        + "    echo \"Locked resource reservedBy 3-2: ${lr.getReservedBy()}\"\n"
                        + "    echo \"Unlocking parallel closure 3\"\n"
                        + "    semaphore 'p3-in-lock'\n" // Java controls when p3 exits lock
                        + "  }\n"
                        + "  echo \"Locked resource cause 3-3: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 3-3: ${lr.getReservedBy()}\"\n"
                        + "},\n"
                        // Add some pressure to try for race conditions:
                        + "p4: { semaphore 'p4'; lock(label: 'label1') { } },\n"
                        + "p5: { semaphore 'p5'; lock(label: 'label1') { } },\n"
                        + "p6: { semaphore 'p6'; lock(label: 'label1') { } },\n"
                        + "p7: { semaphore 'p7'; lock(label: 'label1') { } },\n"
                        + "p8: { semaphore 'p8'; lock(label: 'label1') { } },\n"
                        + "p9: { semaphore 'p9'; lock(label: 'label1') { } }\n"
                        + "\necho \"Survived the test\"\n"
                        + "}", // timeout wrapper
                false));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

        // --- Phase 1: p1 holds lock exclusively (p2,p3 blocked) ---
        j.waitForMessage("Locked resource cause 1-1", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);

        j.waitForMessage("Locked resource cause 1-2", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);

        // Let p1 exit lock closure
        SemaphoreStep.success("p1-inside/1", null);

        // --- Phase 2: p1 released lock but resource is still reserved ---
        j.waitForMessage("Locked resource cause 1-3", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);

        // Bug #1: although resource is reserved, p2 must NOT grab it.
        j.waitForMessage("Locked resource cause 1-4", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);
        LOGGER.info("GOOD: Did not encounter Bug #1 " + "(parallel p2 gets the lock on a still-reserved resource)!");

        // --- Phase 3: p1 calls reset() → p2 gets lock via proceedNextContext ---
        // Ensure p2 is in the waiting queue before p1 calls reset()
        j.waitForMessage("[Label: label1] is not free, waiting for execution ...", b1);

        // p1 calls reset+unreserve, then blocks at semaphore p1-after-reset.
        // p2 gets the lock (dispatched by proceedNextContext), enters closure,
        // does reserve/unreserve cycle, then blocks at semaphore p2-holding.
        SemaphoreStep.success("p1-before-reset/1", null);
        SemaphoreStep.waitForStart("p2-holding/1", b1);

        // Release p3 and pressure stages into the lock queue while p2 holds the lock
        SemaphoreStep.success("p3-wait/1", null);
        for (String s : new String[] {"p4", "p5", "p6", "p7", "p8", "p9"}) {
            SemaphoreStep.success(s + "/1", null);
        }

        // Now let p1 continue to log 1-5 (p2 already holds the lock)
        SemaphoreStep.success("p1-after-reset/1", null);

        j.waitForMessage("Locked resource cause 1-5", b1);
        j.assertLogContains("Locked resource cause 2-1", b1);
        j.assertLogContains("Locked resource cause 2-2", b1);
        LOGGER.info("GOOD: Parallel 2 started after Parallel 1 reset the resource!");

        // p2 holds the lock, reservation cycle (test2-1 → unreserve) already done
        j.assertLogContains("Locked resource reservedBy 1-5: null", b1);

        // The resource is locked by p2 at point 1-5, so "cause 1-5: null" and the
        // un-stucking workaround should never appear.
        for (String line : new String[] {
            "Locked resource cause 1-5: null", "LRM seems stuck; trying to reserve/unreserve", "Secondary lock trick"
        }) {
            j.assertLogNotContains(line, b1);
        }
        LOGGER.info("GOOD: Resource was properly locked at point 1-5 (no un-stucking needed)!");

        j.waitForMessage("Locked resource cause 1-6", b1);
        j.assertLogContains("Locked resource cause 2-2", b1);

        j.waitForMessage("Locked resource cause 2-2", b1);
        j.assertLogContains("Locked resource cause 1-5", b1);
        LOGGER.info("GOOD: lock#2 was taken after p1 reset/released the resource");

        // --- Phase 4: p2 exits lock (still reserved as test2-2) → p3 must NOT get in ---
        SemaphoreStep.success("p2-holding/1", null);

        // Wait for p2 to reach the semaphore before recycle — lock is released but reserved
        SemaphoreStep.waitForStart("p2-before-recycle/1", b1);
        j.assertLogContains("Unlocking parallel closure 2", b1);
        j.assertLogNotContains("Locked resource cause 3-2", b1);
        LOGGER.info("GOOD: lock#3 was NOT taken just after we un-locked closure 2 (keeping lock#2 reserved)");

        // --- Phase 5: p2 recycles → p3 gets lock ---
        // p2 calls recycle(), p3 gets the lock, then p2 blocks at p2-after-recycle.
        // Wait for p3 to actually log 3-2 before letting p2 log 2-4.
        SemaphoreStep.success("p2-before-recycle/1", null);
        j.waitForMessage("Locked resource cause 3-2", b1);
        SemaphoreStep.success("p2-after-recycle/1", null);

        j.waitForMessage("Locked resource cause 2-4", b1);
        j.assertLogContains("Locked resource cause 3-2", b1);
        LOGGER.info("GOOD: lock#3 was taken just after we recycled lock#2");

        // Let p3 finish
        SemaphoreStep.success("p3-in-lock/1", null);

        j.assertLogContains(", waiting for execution ...", b1);

        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        j.assertLogContains("Survived the test", b1);
    }
}
