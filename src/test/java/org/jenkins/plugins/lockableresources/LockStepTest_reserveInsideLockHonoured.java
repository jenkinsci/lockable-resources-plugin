package org.jenkins.plugins.lockableresources;

import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class LockStepTest_reserveInsideLockHonoured extends LockStepTestBase {

    private static final Logger LOGGER = Logger.getLogger(LockStepTest_reserveInsideLockHonoured.class.getName());

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    // @Issue("JENKINS-XXXXX")
    public void reserveInsideLockHonoured() throws Exception {
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
                        // + "    semaphore 'wait-inside'\n"
                        + "    echo \"Locked resource cause 1-2a: ${lr.getLockCause()}\"\n"
                        + "    echo \"Locked resource reservedBy 1-2a: ${lr.getReservedBy()}\"\n"
                        + "    if (!res) {\n"
                        + "        echo \"LockableResourcesManager did not reserve an already locked resource; hack it!\"\n"
                        + "        lr.setReservedBy('test2b')\n"
                        + "        echo \"Locked resource cause 1-2b: ${lr.getLockCause()}\"\n"
                        + "        echo \"Locked resource reservedBy 1-2b: ${lr.getReservedBy()}\"\n"
                        + "    }\n"
                        + "    echo \"Unlocking parallel closure 1\"\n"
                        + "  }\n"
                        + "  echo \"Locked resource cause 1-3 (after unlock): ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-3: ${lr.getReservedBy()}\"\n"
                        + "  echo \"Ended locked parallel closure 1 with resource reserved, sleeping...\"\n"
                        + "  sleep (5)\n"
                        + "  echo \"Locked resource cause 1-4: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-4: ${lr.getReservedBy()}\"\n"
                        + "  echo \"Resetting Locked resource via LRM and sleeping ...\"\n"
                        + "  "
                        + lmget
                        + ".reset([lr])\n"
                        + "  sleep (5)\n"
                        + "  echo \"Un-reserving Locked resource via LRM and sleeping...\"\n"
                        + "  "
                        + lmget
                        + ".unreserve([lr])\n"
                        + "  sleep (5)\n"
                        // Note: the unlock attempt here might steal this resource
                        // from another parallel stage, so we don't do it:
                        // + "  echo \"Un-locking Locked resource via LRM and sleeping...\"\n"
                        // + "  " + lmget + ".unlock([lr], null)\n"
                        // + "  sleep (5)\n"
                        + "  echo \"Locked resource cause 1-5: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-5: ${lr.getReservedBy()}\"\n"
                        + "  sleep (5)\n"
                        + "  if (lr.getLockCause() == null) {\n"
                        + "    echo \"LRM seems stuck; trying to reserve/unreserve this resource by LRM methods\"\n"
                        // + "    lock(label: 'label1') { echo \"Secondary lock trick\" }\n"
                        + "    if ("
                        + lmget
                        + ".reserve([lr], 'unstucker')) {\n"
                        + "        echo \"Secondary lock trick\"\n"
                        + "        "
                        + lmget
                        + ".unreserve([lr])\n"
                        + "    } else { echo \"Could not reserve by LRM methods as 'unstucker'\" }\n"
                        + "  }\n"
                        + "  sleep (5)\n"
                        + "  echo \"Locked resource cause 1-6: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-6: ${lr.getReservedBy()}\"\n"
                        + "},\n"
                        + "p2: {\n"
                        // + "  semaphore 'wait-outside'\n"
                        + "  org.jenkins.plugins.lockableresources.LockableResource lr = null\n"
                        + "  echo \"Locked resource cause 2-1: not locked yet\"\n"
                        + "  lock(label: 'label1', variable: 'someVar2') {\n"
                        + "    echo \"VAR2 IS $env.someVar2\"\n"
                        + "    lr = "
                        + lmget
                        + ".fromName(env.someVar2)\n"
                        + "    sleep (1)\n"
                        + "    echo \"Locked resource cause 2-2: ${lr.getLockCause()}\"\n"
                        + "    echo \"Locked resource reservedBy 2-2: ${lr.getReservedBy()}\"\n"
                        + "    echo \"Setting (directly) and dropping (via LRM) a reservation on locked resource\"\n"
                        + "    lr.reserve('test2-1')\n"
                        + "    sleep (3)\n"
                        + "    "
                        + lmget
                        + ".unreserve([lr])\n"
                        + "    echo \"Just sleeping...\"\n"
                        + "    sleep (20)\n"
                        + "    echo \"Setting (directly) a reservation on locked resource\"\n"
                        + "    lr.reserve('test2-2')\n"
                        + "    echo \"Unlocking parallel closure 2\"\n"
                        + "  }\n"
                        + "  echo \"Locked resource cause 2-3: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 2-3: ${lr.getReservedBy()}\"\n"
                        + "  sleep (5)\n"
                        + "  echo \"Recycling (via LRM) the reserved not-locked resource\"\n"
                        + "  "
                        + lmget
                        + ".recycle([lr])\n"
                        + "  sleep (5)\n"
                        + "  echo \"Locked resource cause 2-4: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 2-4: ${lr.getReservedBy()}\"\n"
                        + "},\n"
                        // Test that reserve/unreserve in p2 did not "allow" p3 to kidnap the lock:
                        + "p3: {\n"
                        + "  org.jenkins.plugins.lockableresources.LockableResource lr = null\n"
                        + "  echo \"Locked resource cause 3-1: not locked yet\"\n"
                        + "  sleep 1\n"
                        + "  lock(label: 'label1', variable: 'someVar3') {\n"
                        + "    echo \"VAR3 IS $env.someVar3\"\n"
                        + "    lr = "
                        + lmget
                        + ".fromName(env.someVar3)\n"
                        + "    echo \"Locked resource cause 3-2: ${lr.getLockCause()}\"\n"
                        + "    echo \"Locked resource reservedBy 3-2: ${lr.getReservedBy()}\"\n"
                        + "    echo \"Just sleeping...\"\n"
                        + "    sleep (10)\n"
                        + "    echo \"Unlocking parallel closure 3\"\n"
                        + "  }\n"
                        + "  echo \"Locked resource cause 3-3: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 3-3: ${lr.getReservedBy()}\"\n"
                        + "},\n"
                        // Add some pressure to try for race conditions:
                        + "p4: { sleep 2; lock(label: 'label1') { sleep 1 } },\n"
                        + "p5: { sleep 2; lock(label: 'label1') { sleep 3 } },\n"
                        + "p6: { sleep 2; lock(label: 'label1') { sleep 2 } },\n"
                        + "p7: { sleep 2; lock(label: 'label1') { sleep 1 } },\n"
                        + "p8: { sleep 2; lock(label: 'label1') { sleep 2 } },\n"
                        + "p9: { sleep 2; lock(label: 'label1') { sleep 1 } }\n"
                        + "\necho \"Survived the test\"\n"
                        + "}", // timeout wrapper
                false));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

        j.waitForMessage("Locked resource cause 1-1", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);

        j.waitForMessage("Locked resource cause 1-2", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);

        j.waitForMessage("Locked resource cause 1-3", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);

        // Bug #1 happens here (without further patch):
        // although resource is seen as reserved, it is
        // grabbed anyway by the other parallel thread
        // which is already waiting. Notably, log is like:
        //  62.908 [setReservedByInsideLockHonoured #1] Lock acquired on [Label: label1]
        //  62.909 [setReservedByInsideLockHonoured #1] Lock released on resource [Label: label1]
        // and the consistent ordering of acquired first,
        // released later is unsettling.
        j.waitForMessage("Locked resource cause 1-4", b1);
        // Note: stage in test has a sleep(1) to reduce chances that
        // this line is noticed in log although it is there AFTER 1-4:
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);
        LOGGER.info("GOOD: Did not encounter Bug #1 " + "(parallel p2 gets the lock on a still-reserved resource)!");

        j.waitForMessage("Locked resource cause 1-5", b1);
        // This line might not strictly be required,
        // but we are processing a parallel pipeline
        // and many seconds were spent sleeping, so:
        j.assertLogContains("Locked resource cause 2-1", b1);
        // Here the other parallel stage may have already started
        // (we try to recycle the resource between 1-4 and 1-5):
        // j.assertLogNotContains("Locked resource cause 2-2", b1);
        // j.assertLogNotContains("Locked resource cause 2-3", b1);

        // Bug #2 happens here: even after the resource is known
        // to be un-reserved, resources already looping waiting
        // for it (after the fix for Bug #1) are not "notified".
        // Adding and removing the resource helps unblock this.
        boolean sawBug2a = false;
        try {
            j.waitForMessage("Locked resource cause 1-6", b1);
            j.assertLogContains("Locked resource cause 2-2", b1);
        } catch (java.lang.AssertionError t1) {
            sawBug2a = true;
            LOGGER.info("Bug #2a (Parallel 2 did not start after Parallel 1 finished "
                    + "and resource later released) currently tolerated");
            // LOGGER.info(t1.toString());
            // throw t1;
        }
        if (!sawBug2a) {
            LOGGER.info("GOOD: Did not encounter Bug #2a "
                    + "(Parallel 2 did not start after Parallel 1 finished "
                    + "and resource later released)!");
        }

        // If the bug is resolved, then by the time we get to 1-5
        // the resource should be taken by the other parallel stage
        // and so not locked by not-"null"; reservation should be away though
        boolean sawBug2b = false;
        j.assertLogContains("Locked resource reservedBy 1-5: null", b1);
        for (String line : new String[] {
            "Locked resource cause 1-5: null", "LRM seems stuck; trying to reserve/unreserve", "Secondary lock trick"
        }) {
            try {
                j.assertLogNotContains(line, b1);
            } catch (java.lang.AssertionError t2) {
                sawBug2b = true;
                LOGGER.info("Bug #2b (LRM required un-stucking) currently tolerated: " + line);
                // LOGGER.info(t2.toString());
                // throw t2;
            }
        }
        if (!sawBug2b) {
            LOGGER.info("GOOD: Did not encounter Bug #2b " + "(LRM required un-stucking)!");
        }

        j.waitForMessage("Locked resource cause 2-2", b1);
        j.assertLogContains("Locked resource cause 1-5", b1);
        LOGGER.info("GOOD: lock#2 was taken after we un-reserved lock#1");

        j.waitForMessage("Unlocking parallel closure 2", b1);
        j.assertLogNotContains("Locked resource cause 3-2", b1);
        LOGGER.info("GOOD: lock#3 was NOT taken just after we un-locked closure 2 (keeping lock#2 reserved)");

        // After 2-3 we lrm.recycle() the lock so it should
        // go to the next bidder
        j.waitForMessage("Locked resource cause 2-4", b1);
        j.assertLogContains("Locked resource cause 3-2", b1);
        LOGGER.info("GOOD: lock#3 was taken just after we recycled lock#2");

        j.assertLogContains("is locked, waiting...", b1);

        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        j.assertLogContains("Survived the test", b1);
    }
}
