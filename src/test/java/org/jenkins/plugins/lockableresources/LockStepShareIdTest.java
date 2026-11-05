package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * lock(shareId: ...): downstream builds started with the {@code build} step, and nested or parallel
 * blocks of the same build, join a hold instead of waiting.
 */
@WithJenkins
class LockStepShareIdTest extends LockStepTestBase {

    /** Declared (not ephemeral) resource, so it is still there to inspect once it is free. */
    private static void declare(String... names) {
        for (String name : names) {
            LockableResourcesManager.get().createResourceWithLabel(name, "share-test");
        }
    }

    private static LockableResource res(String name) {
        return LockableResourcesManager.get().fromName(name);
    }

    private static List<String> holders(String name) {
        LockableResource r = res(name);
        assertNotNull(r, name);
        return r.getShareHolderBuilds();
    }

    private static WorkflowJob job(JenkinsRule j, String name, String script) throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, name);
        p.setDefinition(new CpsFlowDefinition(script, true));
        return p;
    }

    /** Waits until the build step has started build #1 of {@code p}. */
    private static WorkflowRun firstBuild(WorkflowJob p) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        WorkflowRun b;
        while ((b = p.getBuildByNumber(1)) == null) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("no build of " + p.getFullName() + " was started");
            }
            Thread.sleep(100);
        }
        return b;
    }

    private static List<String> inUse(WorkflowRun b) {
        return LockedResourcesBuildAction.findAndInitAction(b).getReadOnlyResourcesInUse();
    }

    @Test
    void buildStepWaitTrue(JenkinsRule j) throws Exception {
        declare("r1");
        WorkflowJob child = job(j, "child", "lock(resource: 'r1', shareId: 'x') { semaphore 'child' }");
        WorkflowJob parent = job(j, "parent", """
                lock(resource: 'r1', shareId: 'x') {
                  build job: 'child', wait: true
                  semaphore 'parent'
                }
                """);

        WorkflowRun p = parent.scheduleBuild2(0).waitForStart();
        WorkflowRun c = firstBuild(child);
        SemaphoreStep.waitForStart("child/1", c);
        j.assertLogContains("Joining the hold of [r1] under share id 'x'", c);
        assertEquals(Arrays.asList(p.getExternalizableId(), c.getExternalizableId()), holders("r1"));
        assertEquals(Collections.singletonList("r1"), inUse(c));

        // the child leaves first, the parent keeps the resource
        SemaphoreStep.success("child/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(c));
        SemaphoreStep.waitForStart("parent/1", p);
        assertTrue(res("r1").isLocked());
        assertEquals(Collections.singletonList(p.getExternalizableId()), holders("r1"));
        assertTrue(inUse(c).isEmpty());

        SemaphoreStep.success("parent/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(p));
        assertFalse(res("r1").isLocked());
        assertNull(res("r1").getShareId());
        assertTrue(holders("r1").isEmpty());
    }

    @Test
    void buildStepWaitFalseChainAndReleaseOnLastHolder(JenkinsRule j) throws Exception {
        declare("r1");
        WorkflowJob parent = job(j, "parent", """
                lock(resource: 'r1', shareId: 'x') {
                  build job: 'child', wait: false
                  semaphore 'parent'
                }
                """);
        WorkflowJob child = job(j, "child", """
                lock(resource: 'r1', shareId: 'x') {
                  build job: 'grandchild', wait: false
                  semaphore 'child'
                }
                """);
        WorkflowJob grandchild = job(j, "grandchild", "lock(resource: 'r1', shareId: 'x') { semaphore 'grandchild' }");

        WorkflowRun p = parent.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("parent/1", p);
        WorkflowRun c = firstBuild(child);
        SemaphoreStep.waitForStart("child/1", c);
        // the chain of nested upstream causes reaches the parent
        WorkflowRun g = firstBuild(grandchild);
        SemaphoreStep.waitForStart("grandchild/1", g);
        assertEquals(
                Arrays.asList(p.getExternalizableId(), c.getExternalizableId(), g.getExternalizableId()),
                holders("r1"));

        // the parent leaves first: the downstream builds keep the resource
        SemaphoreStep.success("parent/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(p));
        assertTrue(res("r1").isLocked());
        assertEquals(c, res("r1").getBuild());
        assertEquals(Arrays.asList(c.getExternalizableId(), g.getExternalizableId()), holders("r1"));
        assertTrue(inUse(p).isEmpty());

        SemaphoreStep.success("child/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(c));
        assertTrue(res("r1").isLocked());
        assertEquals(g, res("r1").getBuild());

        SemaphoreStep.success("grandchild/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(g));
        assertFalse(res("r1").isLocked());
        assertTrue(holders("r1").isEmpty());
    }

    @Test
    void ephemeralResourceRemovedOnlyWhenLastHolderLeaves(JenkinsRule j) throws Exception {
        WorkflowJob parent = job(j, "parent", """
                lock(resource: 'eph', shareId: 'x') {
                  build job: 'child', wait: false
                  semaphore 'parent'
                }
                """);
        WorkflowJob child = job(j, "child", "lock(resource: 'eph', shareId: 'x') { semaphore 'child' }");

        WorkflowRun p = parent.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("parent/1", p);
        assertTrue(res("eph").isEphemeral());
        WorkflowRun c = firstBuild(child);
        SemaphoreStep.waitForStart("child/1", c);

        SemaphoreStep.success("parent/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(p));
        assertNotNull(res("eph"));
        assertTrue(res("eph").isLocked());

        SemaphoreStep.success("child/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(c));
        assertNull(res("eph"));
    }

    @Test
    void queuedShareRequestGetsEphemeralResourceAfterItWasRemoved(JenkinsRule j) throws Exception {
        // regression: the ephemeral resource is removed when its holder leaves; a share request
        // waiting for it must re-create it, as a plain request does
        WorkflowJob holder = job(j, "holder", "lock('eph') { semaphore 'holder' }");
        WorkflowJob waiter = job(j, "waiter", "lock(resource: 'eph', shareId: 'x') { echo 'waiter inside' }");

        WorkflowRun h = holder.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("holder/1", h);
        WorkflowRun w = waiter.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution", w);

        SemaphoreStep.success("holder/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(h));
        j.assertBuildStatusSuccess(j.waitForCompletion(w));
        j.assertLogContains("waiter inside", w);
        assertNull(res("eph"));
    }

    @Test
    void nestedBlocksOfSameBuild(JenkinsRule j) throws Exception {
        declare("r1", "r2");
        WorkflowJob p = job(j, "p", """
                lock('r1') {
                  lock(resource: 'r1', shareId: 'x') {
                    lock(resource: 'r1', shareId: 'x', extra: [[resource: 'r2']]) {
                      semaphore 'inner'
                    }
                  }
                  semaphore 'outer'
                }
                echo 'after'
                """);
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("inner/1", b);
        // the outer plain hold keeps its (absent) share id; r2 is a new hold of the innermost block
        assertNull(res("r1").getShareId());
        assertEquals(3, res("r1").getShareHolders().size());
        assertEquals("x", res("r2").getShareId());

        SemaphoreStep.success("inner/1", null);
        SemaphoreStep.waitForStart("outer/1", b);
        assertTrue(res("r1").isLocked());
        assertEquals(1, res("r1").getShareHolders().size());
        assertFalse(res("r2").isLocked());
        assertEquals(Collections.singletonList("r1"), inUse(b));

        SemaphoreStep.success("outer/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        assertFalse(res("r1").isLocked());
        assertTrue(inUse(b).isEmpty());
    }

    /** Issue #549: an inner shared lock of the resource the declarative options lock already holds. */
    @Test
    void declarativeOptionsLockThenInnerShareIdLockEntersWithoutWaiting(JenkinsRule j) throws Exception {
        declare("DUT1");
        WorkflowJob p = job(j, "p", """
                pipeline {
                  agent none
                  options { lock('DUT1') }
                  stages {
                    stage('test') {
                      steps {
                        lock(resource: 'DUT1', shareId: 'x') {
                          echo 'inner entered'
                          semaphore 'inner'
                        }
                        semaphore 'outer'
                      }
                    }
                  }
                }
                """);
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("inner/1", b);
        j.assertLogContains("inner entered", b);
        j.assertLogContains("Joining the hold of [DUT1] under share id 'x'", b);
        j.assertLogNotContains("is not free", b);
        assertTrue(LockableResourcesManager.get().getCurrentQueuedContext().isEmpty());
        // rule (b): the plain options hold keeps no share id; both blocks of this build hold it
        assertNull(res("DUT1").getShareId());
        assertEquals(2, res("DUT1").getShareHolders().size());
        assertEquals(Collections.singletonList(b.getExternalizableId()), holders("DUT1"));

        // the inner block ends: the options lock still holds the resource
        SemaphoreStep.success("inner/1", null);
        SemaphoreStep.waitForStart("outer/1", b);
        assertTrue(res("DUT1").isLocked());
        assertEquals(1, res("DUT1").getShareHolders().size());
        assertEquals(Collections.singletonList("DUT1"), inUse(b));

        SemaphoreStep.success("outer/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        assertFalse(res("DUT1").isLocked());
        assertTrue(inUse(b).isEmpty());
    }

    @Test
    void nestedBlockWithOtherShareIdWaits(JenkinsRule j) throws Exception {
        declare("r1");
        WorkflowJob p = job(j, "p", """
                lock(resource: 'r1', shareId: 'x') {
                  lock(resource: 'r1', shareId: 'y') {
                    echo 'inner'
                  }
                }
                """);
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution", b);
        j.assertLogNotContains("inner", b);
        b.doStop();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b));
        assertFalse(res("r1").isLocked());
    }

    @Test
    void parallelBranchesShare(JenkinsRule j) throws Exception {
        declare("r1");
        WorkflowJob p = job(j, "p", """
                parallel a: {
                  lock(resource: 'r1', shareId: 'x') { semaphore 'a' }
                }, b: {
                  lock(resource: 'r1', shareId: 'x') { semaphore 'b' }
                }
                """);
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("a/1", b);
        SemaphoreStep.waitForStart("b/1", b);
        assertEquals(2, res("r1").getShareHolders().size());

        SemaphoreStep.success("a/1", null);
        j.waitForMessage("Lock released on resource", b);
        assertTrue(res("r1").isLocked());
        assertEquals(1, res("r1").getShareHolders().size());

        SemaphoreStep.success("b/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        assertFalse(res("r1").isLocked());
    }

    @Test
    void hardKillRemovesOnlyTheKilledBuild(JenkinsRule j) throws Exception {
        declare("r1");
        WorkflowJob parent = job(j, "parent", """
                lock(resource: 'r1', shareId: 'x') {
                  build job: 'child', wait: false
                  sleep 600
                }
                """);
        WorkflowJob child = job(j, "child", "lock(resource: 'r1', shareId: 'x') { echo 'child inside'; sleep 600 }");
        WorkflowJob other = job(j, "other", "lock('r1') { echo 'other inside' }");

        WorkflowRun p = parent.scheduleBuild2(0).waitForStart();
        WorkflowRun c = firstBuild(child);
        j.waitForMessage("child inside", c);
        WorkflowRun o = other.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution", o);

        p.doKill();
        j.waitForCompletion(p);
        j.assertBuildStatus(Result.ABORTED, p);
        assertTrue(res("r1").isLocked());
        assertEquals(Collections.singletonList(c.getExternalizableId()), holders("r1"));
        assertEquals(c, res("r1").getBuild());
        j.assertLogNotContains("other inside", o);

        c.doKill();
        j.waitForCompletion(c);
        j.assertBuildStatusSuccess(j.waitForCompletion(o));
        j.assertLogContains("other inside", o);
        assertFalse(res("r1").isLocked());
    }

    @Test
    void abortRemovesOnlyTheAbortedBuildAndFreesWhenNoHolderLeft(JenkinsRule j) throws Exception {
        declare("r1");
        WorkflowJob parent = job(j, "parent", """
                lock(resource: 'r1', shareId: 'x') {
                  build job: 'child', wait: false
                  semaphore 'parent'
                }
                """);
        WorkflowJob child = job(j, "child", "lock(resource: 'r1', shareId: 'x') { semaphore 'child' }");
        WorkflowJob other = job(j, "other", "lock('r1') { echo 'other inside' }");

        WorkflowRun p = parent.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("parent/1", p);
        WorkflowRun c = firstBuild(child);
        SemaphoreStep.waitForStart("child/1", c);
        WorkflowRun o = other.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution", o);

        // a normal stop runs the block end of the parent: only its entry goes
        p.doStop();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(p));
        assertTrue(res("r1").isLocked());
        assertEquals("x", res("r1").getShareId());
        assertEquals(Collections.singletonList(c.getExternalizableId()), holders("r1"));
        assertEquals(c, res("r1").getBuild());
        assertTrue(inUse(p).isEmpty());
        j.assertLogNotContains("other inside", o);

        // the last holder is aborted: the resource is freed and the waiting build enters
        c.doStop();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(c));
        j.assertBuildStatusSuccess(j.waitForCompletion(o));
        j.assertLogContains("other inside", o);
        assertFalse(res("r1").isLocked());
        assertNull(res("r1").getShareId());
        assertTrue(holders("r1").isEmpty());
    }

    @Test
    void buildActionLogsJoinedLeftAcquiredReleased(JenkinsRule j) throws Exception {
        declare("r1");
        WorkflowJob parent = job(j, "parent", """
                lock(resource: 'r1', shareId: 'x') {
                  build job: 'child', wait: false
                  semaphore 'parent'
                }
                """);
        WorkflowJob child = job(j, "child", "lock(resource: 'r1', shareId: 'x') { semaphore 'child' }");

        WorkflowRun p = parent.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("parent/1", p);
        WorkflowRun c = firstBuild(child);
        SemaphoreStep.waitForStart("child/1", c);

        // the parent is the first holder and leaves first; the child joins and is the last holder
        SemaphoreStep.success("parent/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(p));
        SemaphoreStep.success("child/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(c));

        assertEquals(Arrays.asList("try", "acquired", "left"), logSteps(p, "r1"));
        assertEquals(Arrays.asList("try", "joined", "released"), logSteps(c, "r1"));
        for (LockedResourcesBuildAction.LogEntry e : logs(p, "r1")) {
            assertEquals("x", e.getShareId());
        }
        for (LockedResourcesBuildAction.LogEntry e : logs(c, "r1")) {
            assertEquals("x", e.getShareId());
        }
    }

    private static List<LockedResourcesBuildAction.LogEntry> logs(WorkflowRun b, String resource) {
        return LockedResourcesBuildAction.findAndInitAction(b).getReadOnlyLogs().stream()
                .filter(e -> resource.equals(e.getName()))
                .toList();
    }

    private static List<String> logSteps(WorkflowRun b, String resource) {
        return logs(b, resource).stream()
                .map(LockedResourcesBuildAction.LogEntry::getStep)
                .toList();
    }

    @Test
    void nonMatchingIdOrNonAncestorWaits(JenkinsRule j) throws Exception {
        declare("r1");
        WorkflowJob parent = job(j, "parent", """
                lock(resource: 'r1', shareId: 'x') {
                  build job: 'child', wait: false
                  semaphore 'parent'
                }
                """);
        WorkflowJob child = job(j, "child", "lock(resource: 'r1', shareId: 'y') { echo 'child inside' }");
        WorkflowJob unrelated = job(j, "unrelated", "lock(resource: 'r1', shareId: 'x') { echo 'unrelated inside' }");

        WorkflowRun p = parent.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("parent/1", p);

        WorkflowRun c = firstBuild(child);
        j.waitForMessage("is not free, waiting for execution", c);
        isPaused(c, 1, 1);

        WorkflowRun u = unrelated.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution", u);
        j.assertLogContains(
                "[r1] is held under share id 'x' by [" + p.getExternalizableId()
                        + "], but cannot be joined: this build has no upstream build",
                u);
        isPaused(u, 1, 1);
        assertEquals(Collections.singletonList(p.getExternalizableId()), holders("r1"));

        SemaphoreStep.success("parent/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(p));
        j.assertBuildStatusSuccess(j.waitForCompletion(c));
        j.assertBuildStatusSuccess(j.waitForCompletion(u));
        assertFalse(res("r1").isLocked());
    }

    @Test
    void partialRequestEntersOnceTheRestIsFree(JenkinsRule j) throws Exception {
        declare("r1", "r2");
        WorkflowJob other = job(j, "other", "lock('r2') { semaphore 'other' }");
        WorkflowJob parent = job(j, "parent", """
                lock(resource: 'r1', shareId: 'x') {
                  build job: 'child', wait: false
                  semaphore 'parent'
                }
                """);
        WorkflowJob child =
                job(j, "child", "lock(resource: 'r1', shareId: 'x', extra: [[resource: 'r2']]) { semaphore 'child' }");

        WorkflowRun o = other.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("other/1", o);
        WorkflowRun p = parent.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("parent/1", p);

        WorkflowRun c = firstBuild(child);
        j.waitForMessage("is not free, waiting for execution", c);
        assertEquals(1, LockableResourcesManager.get().getCurrentQueuedContext().size());

        SemaphoreStep.success("other/1", null);
        SemaphoreStep.waitForStart("child/1", c);
        assertEquals(Arrays.asList(p.getExternalizableId(), c.getExternalizableId()), holders("r1"));
        assertEquals(c, res("r2").getBuild());
        assertEquals("x", res("r2").getShareId());

        SemaphoreStep.success("child/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(c));
        assertFalse(res("r2").isLocked());
        assertTrue(res("r1").isLocked());
        SemaphoreStep.success("parent/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(p));
        assertFalse(res("r1").isLocked());
    }

    @Test
    void queuedRequestJoinsWhenAncestorAcquires(JenkinsRule j) throws Exception {
        declare("r1");
        WorkflowJob blocker = job(j, "blocker", "lock('r1') { semaphore 'blocker' }");
        WorkflowJob parent = job(j, "parent", """
                parallel a: {
                  lock(resource: 'r1', shareId: 'x') { semaphore 'parent' }
                }, b: {
                  semaphore 'go'
                  build job: 'child', wait: false
                }
                """);
        WorkflowJob child = job(j, "child", "lock(resource: 'r1', shareId: 'x') { semaphore 'child' }");

        WorkflowRun bl = blocker.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("blocker/1", bl);
        WorkflowRun p = parent.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution", p);
        SemaphoreStep.waitForStart("go/1", p);
        SemaphoreStep.success("go/1", null);
        WorkflowRun c = firstBuild(child);
        j.waitForMessage("is not free, waiting for execution", c);
        assertEquals(2, LockableResourcesManager.get().getCurrentQueuedContext().size());

        SemaphoreStep.success("blocker/1", null);
        SemaphoreStep.waitForStart("parent/1", p);
        SemaphoreStep.waitForStart("child/1", c);
        assertEquals(Arrays.asList(p.getExternalizableId(), c.getExternalizableId()), holders("r1"));
        assertTrue(LockableResourcesManager.get().getCurrentQueuedContext().isEmpty());

        SemaphoreStep.success("parent/1", null);
        SemaphoreStep.success("child/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(p));
        j.assertBuildStatusSuccess(j.waitForCompletion(c));
        assertFalse(res("r1").isLocked());
    }

    @Test
    void uiUnlockClearsShareAndLaterBlockEndsDoNothing(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        declare("r1");
        WorkflowJob p = job(j, "p", """
                parallel a: {
                  lock(resource: 'r1', shareId: 'x') { semaphore 'a' }
                }, b: {
                  lock(resource: 'r1', shareId: 'x') { semaphore 'b' }
                }
                """);
        WorkflowJob other = job(j, "other", "lock('r1') { semaphore 'other' }");
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("a/1", b);
        SemaphoreStep.waitForStart("b/1", b);
        WorkflowRun o = other.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution", o);

        lrm.unlockResources(Collections.singletonList(res("r1")));
        SemaphoreStep.waitForStart("other/1", o);
        assertEquals(o, res("r1").getBuild());
        assertEquals(1, res("r1").getShareHolders().size());

        // the share blocks end after their hold was cleared: the new holder keeps the resource
        SemaphoreStep.success("a/1", null);
        SemaphoreStep.success("b/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        assertEquals(o, res("r1").getBuild());

        SemaphoreStep.success("other/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(o));
        assertFalse(res("r1").isLocked());
    }

    @Test
    void shareIdValidation(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("r1", "lbl");
        WorkflowJob p1 = job(j, "p1", "lock(label: 'lbl', shareId: 'x') { echo 'inside' }");
        j.assertLogContains(
                "The 'shareId' parameter cannot be combined with 'label'",
                j.assertBuildStatus(Result.FAILURE, p1.scheduleBuild2(0)));
        WorkflowJob p2 = job(j, "p2", "lock(resource: 'r1', quantity: 1, shareId: 'x') { echo 'inside' }");
        j.assertLogContains(
                "The 'shareId' parameter cannot be combined with 'quantity'",
                j.assertBuildStatus(Result.FAILURE, p2.scheduleBuild2(0)));
        WorkflowJob p3 = job(j, "p3", "lock(resource: 'r1', extra: [[label: 'lbl']], shareId: 'x') { echo 'inside' }");
        j.assertLogContains(
                "cannot be combined with 'extra' entries", j.assertBuildStatus(Result.FAILURE, p3.scheduleBuild2(0)));
        WorkflowJob p4 = job(j, "p4", "lock(resource: 'r1', serverId: 'srv', shareId: 'x') { echo 'inside' }");
        j.assertLogContains(
                "The 'shareId' parameter cannot be used with a remote lock",
                j.assertBuildStatus(Result.FAILURE, p4.scheduleBuild2(0)));
        assertFalse(res("r1").isLocked());
    }
}
