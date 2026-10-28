package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.Result;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

/** Tests for the lock step options onReleaseDelay / onCancelDelay (issue #622). */
class LockStepReleaseDelayTest extends LockStepTestBase {

    /** Upper bound for waiting on a release; far above the delays used here. */
    private static final long WAIT_TIMEOUT_MS = 60_000L;

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void releaseDelayKeepsResourceLockedThenFreesIt() throws Throwable {
        sessions.then(j -> {
            LockableResourcesManager lrm = LockableResourcesManager.get();
            createDeclaredResource("resource1");
            WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("""
                    lock(resource: 'resource1', onReleaseDelay: 5) {
                      echo 'inside lock'
                    }
                    echo 'after lock'
                    semaphore 'after-lock'
                    """, true));
            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

            // the pipeline continues right away, while the resource is still locked
            SemaphoreStep.waitForStart("after-lock/1", b1);
            j.assertLogContains("after lock", b1);
            j.assertLogContains("Lock on resource [Resource: resource1] will be released in 5 seconds", b1);
            LockableResource resource = lrm.fromName("resource1");
            assertTrue(resource.isLocked());
            assertTrue(resource.isReleaseDelayed());
            assertNotNull(resource.getReleaseDelayedUntil());
            assertEquals(b1, resource.getBuild());
            final long releaseAt = resource.getReleaseAt();

            // the build ends; onCompleted must not release the resource early
            SemaphoreStep.success("after-lock/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(b1));
            if (System.currentTimeMillis() < releaseAt) {
                assertTrue(resource.isLocked(), "still in release delay after the build ended");
                assertTrue(resource.isReleaseDelayed());
            }

            waitUntilFree("resource1");
            assertTrue(System.currentTimeMillis() >= releaseAt, "released before the delay was over");
            assertFalse(resource.isReleaseDelayed());
            assertNull(resource.getBuild());
        });
    }

    @Test
    void waitingBuildGetsResourceOnlyAfterDelay() throws Throwable {
        sessions.then(j -> {
            LockableResourcesManager lrm = LockableResourcesManager.get();
            createDeclaredResource("resource1");
            WorkflowJob p1 = j.jenkins.createProject(WorkflowJob.class, "p1");
            p1.setDefinition(new CpsFlowDefinition("""
                    lock(resource: 'resource1', onReleaseDelay: 5) {
                      semaphore 'inside-lock'
                    }
                    """, true));
            WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
            p2.setDefinition(new CpsFlowDefinition("""
                    lock('resource1') {
                      echo 'p2 got the lock'
                    }
                    """, true));

            WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("inside-lock/1", b1);
            WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
            j.waitForMessage("[Resource: resource1] is not free, waiting for execution ...", b2);

            SemaphoreStep.success("inside-lock/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(b1));
            LockableResource resource = lrm.fromName("resource1");
            final long releaseAt = resource.getReleaseAt();
            assertTrue(releaseAt > 0, "b1 keeps the resource for its release delay");
            if (System.currentTimeMillis() < releaseAt) {
                j.assertLogNotContains("p2 got the lock", b2);
                isPaused(b2, 1, 1);
            }

            j.assertBuildStatusSuccess(j.waitForCompletion(b2));
            j.assertLogContains("p2 got the lock", b2);
            assertTrue(System.currentTimeMillis() >= releaseAt, "b2 got the lock before the delay was over");
            waitUntilFree("resource1");
        });
    }

    @Test
    void abortUsesOnCancelDelay() throws Throwable {
        sessions.then(j -> {
            LockableResourcesManager lrm = LockableResourcesManager.get();
            createDeclaredResource("resource1");
            WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("""
                    lock(resource: 'resource1', onReleaseDelay: 300, onCancelDelay: 3) {
                      semaphore 'inside-lock'
                    }
                    """, true));
            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("inside-lock/1", b1);

            final long beforeAbort = System.currentTimeMillis();
            b1.getExecutor().interrupt();
            j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b1));
            j.assertLogContains("Lock on resource [Resource: resource1] will be released in 3 seconds", b1);

            LockableResource resource = lrm.fromName("resource1");
            final long releaseAt = resource.getReleaseAt();
            if (releaseAt > 0) {
                // the 3 seconds onCancelDelay is used, not the 300 seconds onReleaseDelay
                assertTrue(releaseAt <= System.currentTimeMillis() + 3_000L, "uses onCancelDelay");
                assertTrue(releaseAt >= beforeAbort + 3_000L, "starts the delay when the body ends");
            }
            waitUntilFree("resource1");
        });
    }

    @Test
    void abortWithoutCancelDelayReleasesImmediately() throws Throwable {
        sessions.then(j -> {
            LockableResourcesManager lrm = LockableResourcesManager.get();
            createDeclaredResource("resource1");
            WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("""
                    lock(resource: 'resource1', onReleaseDelay: 300) {
                      semaphore 'inside-lock'
                    }
                    """, true));
            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("inside-lock/1", b1);

            b1.getExecutor().interrupt();
            j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b1));
            j.assertLogContains("Lock released on resource [Resource: resource1]", b1);

            LockableResource resource = lrm.fromName("resource1");
            assertFalse(resource.isReleaseDelayed());
            assertFalse(resource.isLocked());
        });
    }

    @Test
    void failureUsesOnReleaseDelayAndManualUnlockEndsIt() throws Throwable {
        sessions.then(j -> {
            LockableResourcesManager lrm = LockableResourcesManager.get();
            createDeclaredResource("resource1");
            WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("""
                    lock(resource: 'resource1', onReleaseDelay: 300, onCancelDelay: 1) {
                      error 'boom'
                    }
                    """, true));
            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
            j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b1));
            j.assertLogContains("Lock on resource [Resource: resource1] will be released in 300 seconds", b1);

            LockableResource resource = lrm.fromName("resource1");
            assertTrue(resource.isLocked());
            assertTrue(resource.isReleaseDelayed());

            // the unlock button on the resources page ends the delay right away
            lrm.unlockResources(Collections.singletonList(resource));
            assertFalse(resource.isReleaseDelayed());
            assertFalse(resource.isLocked());
        });
    }

    @Test
    void zeroDelayBehavesLikeToday() throws Throwable {
        sessions.then(j -> {
            LockableResourcesManager lrm = LockableResourcesManager.get();
            createDeclaredResource("resource1");
            WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("""
                    lock(resource: 'resource1', onReleaseDelay: 0, onCancelDelay: 0) {
                      echo 'inside lock'
                    }
                    semaphore 'after-lock'
                    """, true));
            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("after-lock/1", b1);
            j.assertLogContains("Lock released on resource [Resource: resource1]", b1);

            LockableResource resource = lrm.fromName("resource1");
            assertFalse(resource.isReleaseDelayed());
            assertFalse(resource.isLocked());

            SemaphoreStep.success("after-lock/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        });
    }

    @Test
    void snippetOmitsDefaultDelays() throws Throwable {
        sessions.then(j -> {
            LockStep step = new LockStep("resource1");
            step.setOnReleaseDelay(5);
            Map<String, ?> args = DescribableModel.uninstantiate2_(step).getArguments();
            assertEquals(5L, ((Number) args.get("onReleaseDelay")).longValue());
            assertFalse(args.containsKey("onCancelDelay"), "default 0 is not written");

            step.setOnCancelDelay(-3);
            assertEquals(0, step.onCancelDelay, "negative delays are treated as 0");
        });
    }

    @Test
    void restartDuringDelayStillReleases() throws Throwable {
        // shared between the two sessions; they run in the same JVM
        final AtomicLong releaseAt = new AtomicLong();
        sessions.then(j -> {
            LockableResourcesManager lrm = LockableResourcesManager.get();
            createDeclaredResource("resource1");
            WorkflowJob p1 = j.jenkins.createProject(WorkflowJob.class, "p1");
            p1.setDefinition(new CpsFlowDefinition("""
                    lock(resource: 'resource1', onReleaseDelay: 15) {
                      echo 'inside lock'
                    }
                    """, true));
            WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
            p2.setDefinition(new CpsFlowDefinition("""
                    lock('resource1') {
                      echo 'p2 got the lock'
                    }
                    """, true));

            WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
            j.assertBuildStatusSuccess(j.waitForCompletion(b1));
            LockableResource resource = lrm.fromName("resource1");
            assertTrue(resource.isReleaseDelayed());
            releaseAt.set(resource.getReleaseAt());

            WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
            j.waitForMessage("[Resource: resource1] is not free, waiting for execution ...", b2);
        });

        sessions.then(j -> {
            WorkflowJob p2 = j.jenkins.getItemByFullName("p2", WorkflowJob.class);
            WorkflowRun b2 = p2.getBuildByNumber(1);
            LockableResource resource = LockableResourcesManager.get().fromName("resource1");
            assertNotNull(resource);
            if (System.currentTimeMillis() < releaseAt.get()) {
                // the pending release was persisted and FreeDeadJobs did not free it as a dead build
                assertTrue(resource.isReleaseDelayed());
                assertEquals(releaseAt.get(), resource.getReleaseAt());
                j.assertLogNotContains("p2 got the lock", b2);
            }

            j.assertBuildStatusSuccess(j.waitForCompletion(b2));
            j.assertLogContains("p2 got the lock", b2);
            assertTrue(System.currentTimeMillis() >= releaseAt.get(), "released before the delay was over");
            waitUntilFree("resource1");
        });
    }

    /** A configured (not ephemeral) resource, so it still exists after it is released. */
    private static void createDeclaredResource(String resourceName) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource(resourceName);
        lrm.fromName(resourceName).setEphemeral(false);
    }

    private static void waitUntilFree(String resourceName) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        LockableResource resource = LockableResourcesManager.get().fromName(resourceName);
        assertNotNull(resource);
        while (resource.isLocked()) {
            if (System.currentTimeMillis() > deadline) {
                fail("resource " + resourceName + " is still locked after " + WAIT_TIMEOUT_MS + "ms");
            }
            Thread.sleep(100);
        }
    }
}
