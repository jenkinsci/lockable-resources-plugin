package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/**
 * Tests for issue #773: Resources are not removed from queue when timeout is reached.
 *
 * <p>When a pipeline times out while its lock step is waiting in the queue, the queued context must
 * be removed so the resource can be acquired by other waiting builds.
 */
@WithJenkins
class LockStepTimeoutQueueTest extends LockStepTestBase {

    /**
     * A build waiting for a lock is aborted by a timeout() wrapper. The queued context must be
     * removed so the next waiting build gets the lock.
     */
    @Issue("773")
    @Test
    void timeoutWhileWaitingForLockClearsQueue(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        // b1 holds the lock
        WorkflowJob p1 = j.jenkins.createProject(WorkflowJob.class, "holder");
        p1.setDefinition(new CpsFlowDefinition(
                "lock('resource1') {\n" + "  semaphore 'hold'\n" + "}\n" + "echo 'holder done'", true));
        WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hold/1", b1);

        // b2 tries to lock with a short timeout — will time out while waiting
        WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "timeouter");
        p2.setDefinition(new CpsFlowDefinition(
                "timeout(time: 5, unit: 'SECONDS') {\n"
                        + "  lock('resource1') {\n"
                        + "    echo 'timeouter inside lock'\n"
                        + "  }\n"
                        + "}",
                true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b2);

        // b3 also waits for the lock — queued after b2
        WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "waiter");
        p3.setDefinition(new CpsFlowDefinition(
                "lock('resource1') {\n" + "  semaphore 'waiter'\n" + "}\n" + "echo 'waiter done'", true));
        WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b3);

        // Verify b2 and b3 are both queued
        assertEquals(
                2,
                LockableResourcesManager.get().getCurrentQueuedContext().size(),
                "Both b2 and b3 should be in the queue");

        // Wait for b2 to time out
        j.waitForCompletion(b2);
        j.assertBuildStatus(Result.ABORTED, b2);
        j.assertLogContains("Timeout has been exceeded", b2);

        // After b2 times out, it must be removed from the queue — only b3 remains
        assertEquals(
                1,
                LockableResourcesManager.get().getCurrentQueuedContext().size(),
                "b2 must be removed from the queue after timeout");

        // Release the lock from b1 → b3 should get it (not stuck behind dead b2)
        SemaphoreStep.success("hold/1", null);
        j.waitForCompletion(b1);

        // b3 should acquire the lock
        SemaphoreStep.waitForStart("waiter/1", b3);
        j.assertLogContains("Lock acquired on [Resource: resource1]", b3);

        SemaphoreStep.success("waiter/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        j.assertLogContains("waiter done", b3);

        // Queue must be fully empty
        assertTrue(
                LockableResourcesManager.get().getCurrentQueuedContext().isEmpty(),
                "Queue must be empty after all builds complete");
    }

    /**
     * A build waiting for a lock by label is aborted. The queued context must be removed and the
     * next waiter proceeds.
     */
    @Issue("773")
    @Test
    void abortWhileWaitingForLockByLabelClearsQueue(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");

        // b1 holds the lock
        WorkflowJob p1 = j.jenkins.createProject(WorkflowJob.class, "holder");
        p1.setDefinition(
                new CpsFlowDefinition("lock(label: 'label1', quantity: 1) {\n" + "  semaphore 'hold'\n" + "}", true));
        WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hold/1", b1);

        // b2 waits for the lock
        WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "aborter");
        p2.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', quantity: 1) {\n" + "  semaphore 'aborter'\n" + "}", true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        j.waitForMessage(", waiting for execution ...", b2);

        // b3 also waits
        WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "waiter");
        p3.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', quantity: 1) {\n" + "  semaphore 'waiter'\n" + "}\n" + "echo 'waiter done'",
                true));
        WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
        j.waitForMessage(", waiting for execution ...", b3);

        assertEquals(
                2,
                LockableResourcesManager.get().getCurrentQueuedContext().size(),
                "Both b2 and b3 should be in the queue");

        // Abort b2 (simulates user abort or parent timeout propagation)
        b2.getExecutor().interrupt();
        j.waitForCompletion(b2);
        j.assertBuildStatus(Result.ABORTED, b2);

        // After abort, b2 must be removed from the queue
        assertEquals(
                1,
                LockableResourcesManager.get().getCurrentQueuedContext().size(),
                "b2 must be removed from the queue after abort");

        // Release the lock → b3 gets it
        SemaphoreStep.success("hold/1", null);
        j.waitForCompletion(b1);

        SemaphoreStep.waitForStart("waiter/1", b3);
        j.assertLogContains("Lock acquired on [Label: label1", b3);

        SemaphoreStep.success("waiter/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        j.assertLogContains("waiter done", b3);

        assertTrue(
                LockableResourcesManager.get().getCurrentQueuedContext().isEmpty(),
                "Queue must be empty after all builds complete");
    }

    /**
     * Multiple builds are queued for a lock. The middle one times out. The remaining builds must
     * still proceed in order.
     */
    @Issue("773")
    @Test
    void timeoutMiddleBuildInQueuePreservesOrder(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        // b1 holds the lock
        WorkflowJob holder = j.jenkins.createProject(WorkflowJob.class, "holder");
        holder.setDefinition(new CpsFlowDefinition("lock('resource1') { semaphore 'hold' }\necho 'holder done'", true));
        WorkflowRun b1 = holder.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hold/1", b1);

        // b2 waits (no timeout)
        WorkflowJob first = j.jenkins.createProject(WorkflowJob.class, "first");
        first.setDefinition(new CpsFlowDefinition("lock('resource1') { semaphore 'first' }\necho 'first done'", true));
        WorkflowRun b2 = first.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b2);

        // b3 waits with timeout — will be aborted
        WorkflowJob middle = j.jenkins.createProject(WorkflowJob.class, "middle");
        middle.setDefinition(new CpsFlowDefinition(
                "timeout(time: 5, unit: 'SECONDS') {\n"
                        + "  lock('resource1') {\n"
                        + "    echo 'middle should never get here'\n"
                        + "  }\n"
                        + "}",
                true));
        WorkflowRun b3 = middle.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b3);

        // b4 waits (no timeout)
        WorkflowJob last = j.jenkins.createProject(WorkflowJob.class, "last");
        last.setDefinition(new CpsFlowDefinition("lock('resource1') { semaphore 'last' }\necho 'last done'", true));
        WorkflowRun b4 = last.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b4);

        assertEquals(3, LockableResourcesManager.get().getCurrentQueuedContext().size());

        // b3 times out
        j.waitForCompletion(b3);
        j.assertBuildStatus(Result.ABORTED, b3);
        j.assertLogNotContains("middle should never get here", b3);

        // Queue should now have b2 and b4
        assertEquals(
                2,
                LockableResourcesManager.get().getCurrentQueuedContext().size(),
                "Only b2 and b4 should remain in the queue");

        // Release lock → b2 gets it first (FIFO order preserved)
        SemaphoreStep.success("hold/1", null);
        j.waitForCompletion(b1);

        SemaphoreStep.waitForStart("first/1", b2);
        j.assertLogContains("Lock acquired on [Resource: resource1]", b2);

        // b4 is still waiting
        j.assertLogNotContains("Lock acquired on", b4);

        // Release b2 → b4 gets the lock
        SemaphoreStep.success("first/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));

        SemaphoreStep.waitForStart("last/1", b4);
        j.assertLogContains("Lock acquired on [Resource: resource1]", b4);

        SemaphoreStep.success("last/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b4));
        j.assertLogContains("last done", b4);

        assertTrue(LockableResourcesManager.get().getCurrentQueuedContext().isEmpty());
    }

    /**
     * Simulate the "extreme prejudice" scenario: a build waiting for a lock is hard-killed via
     * doKill(). This bypasses the normal step shutdown — the queue entry becomes stale and must be
     * cleaned up by the isValid() fallback in getNextQueuedContext() when the resource is freed.
     */
    @Issue("773")
    @Test
    void hardKillWhileWaitingForLockClearsQueueViaIsValid(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        // b1 holds the lock
        WorkflowJob holder = j.jenkins.createProject(WorkflowJob.class, "holder");
        holder.setDefinition(new CpsFlowDefinition("lock('resource1') { semaphore 'hold' }\necho 'holder done'", true));
        WorkflowRun b1 = holder.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hold/1", b1);

        // b2 waits for the lock — will be hard-killed
        WorkflowJob victim = j.jenkins.createProject(WorkflowJob.class, "victim");
        victim.setDefinition(
                new CpsFlowDefinition("lock('resource1') {\n" + "  echo 'victim inside lock'\n" + "}", true));
        WorkflowRun b2 = victim.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b2);
        isPaused(b2, 1, 1);

        // b3 also waits
        WorkflowJob waiter = j.jenkins.createProject(WorkflowJob.class, "waiter");
        waiter.setDefinition(
                new CpsFlowDefinition("lock('resource1') { semaphore 'waiter' }\necho 'waiter done'", true));
        WorkflowRun b3 = waiter.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b3);
        isPaused(b3, 1, 1);

        assertEquals(2, LockableResourcesManager.get().getCurrentQueuedContext().size());

        // Hard-kill b2 — this is the "extreme prejudice" path
        b2.doKill();
        j.waitForMessage("Hard kill!", b2);
        j.waitForCompletion(b2);
        j.assertBuildStatus(Result.ABORTED, b2);

        // Release the lock → proceedNextContext() runs → isValid() must detect b2
        // is dead and skip it, then give the lock to b3
        SemaphoreStep.success("hold/1", null);
        j.waitForCompletion(b1);

        // b3 must get the lock — if b2's stale entry blocks the queue, this will hang
        SemaphoreStep.waitForStart("waiter/1", b3);
        j.assertLogContains("Lock acquired on [Resource: resource1]", b3);
        j.assertLogNotContains("victim inside lock", b2);

        SemaphoreStep.success("waiter/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        j.assertLogContains("waiter done", b3);

        assertTrue(
                LockableResourcesManager.get().getCurrentQueuedContext().isEmpty(),
                "Queue must be empty after all builds complete");
    }

    /**
     * A build that was waiting for a lock is hard-killed while it is the ONLY waiter. When the
     * resource is later freed, the stale entry must be cleaned up and the queue must be empty. A
     * new build must then be able to acquire the lock immediately.
     */
    @Issue("773")
    @Test
    void hardKillOnlyWaiterDoesNotBlockFutureBuilds(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        // b1 holds the lock
        WorkflowJob holder = j.jenkins.createProject(WorkflowJob.class, "holder");
        holder.setDefinition(new CpsFlowDefinition("lock('resource1') { semaphore 'hold' }\necho 'holder done'", true));
        WorkflowRun b1 = holder.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hold/1", b1);

        // b2 waits — will be hard-killed
        WorkflowJob victim = j.jenkins.createProject(WorkflowJob.class, "victim");
        victim.setDefinition(new CpsFlowDefinition("lock('resource1') { echo 'victim inside' }", true));
        WorkflowRun b2 = victim.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b2);

        assertEquals(1, LockableResourcesManager.get().getCurrentQueuedContext().size());

        // Hard-kill b2
        b2.doKill();
        j.waitForCompletion(b2);
        j.assertBuildStatus(Result.ABORTED, b2);

        // Release the lock from b1
        SemaphoreStep.success("hold/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        // A new build must be able to acquire the lock immediately
        WorkflowJob fresh = j.jenkins.createProject(WorkflowJob.class, "fresh");
        fresh.setDefinition(new CpsFlowDefinition("lock('resource1') { semaphore 'fresh' }\necho 'fresh done'", true));
        WorkflowRun b3 = fresh.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("fresh/1", b3);
        j.assertLogContains("Lock acquired on [Resource: resource1]", b3);

        SemaphoreStep.success("fresh/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        j.assertLogContains("fresh done", b3);

        assertTrue(LockableResourcesManager.get().getCurrentQueuedContext().isEmpty());
    }
}
