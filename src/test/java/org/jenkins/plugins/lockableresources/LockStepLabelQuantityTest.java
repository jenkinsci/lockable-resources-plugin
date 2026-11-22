package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.queue.QueueTaskFuture;
import hudson.util.OneShotEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Regression tests for GitHub issue #890: "Certain conditions might lock more resources than
 * required quantity".
 *
 * <p>Real pipeline (and freestyle) builds wait on a {@link SemaphoreStep} (or a blocking builder) so
 * the test controls when they release; every build that gets its lock must hold exactly the number
 * of resources it asked for, with no resource counted twice.
 */
@WithJenkins
class LockStepLabelQuantityTest extends LockStepTestBase {

    private static final String LABEL = "FOO";

    @BeforeEach
    void setUp() {
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    }

    // ---------------------------------------------------------------------------
    /** Same as the reporters' check: resources whose current build is the given run. */
    private static List<String> heldBy(Run<?, ?> run) {
        List<String> held = new ArrayList<>();
        for (LockableResource r : LockableResourcesManager.get().getResources()) {
            if (run.equals(r.getBuild())) {
                held.add(r.getName());
            }
        }
        return held;
    }

    /**
     * Checks the run holds exactly {@code expected} resources, and that the build's own list of
     * resources in use has the same size and no name twice.
     */
    private static List<String> assertHolds(Run<?, ?> run, int expected) {
        List<String> held = heldBy(run);
        assertEquals(expected, held.size(), run.getFullDisplayName() + " holds " + held);
        List<String> inUse = new ArrayList<>(
                LockedResourcesBuildAction.findAndInitAction(run).getCurrentUsedResourceNames());
        assertEquals(
                new HashSet<>(inUse).size(),
                inUse.size(),
                run.getFullDisplayName() + " build action lists a resource twice: " + inUse);
        assertEquals(expected, inUse.size(), run.getFullDisplayName() + " build action lists " + inUse);
        return held;
    }

    private static void createResources(int count) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        for (int i = 1; i <= count; i++) {
            lrm.createResourceWithLabel("res_" + i, LABEL);
        }
    }

    /** Starts a build locking {@code quantity} resources of the label (0 = no quantity, i.e. all). */
    private static WorkflowRun start(JenkinsRule j, String name, int quantity) throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, name);
        String q = quantity > 0 ? ", quantity: " + quantity : "";
        p.setDefinition(new CpsFlowDefinition(
                "lock(label: '" + LABEL + "'" + q + ") {\n"
                        + "  semaphore '" + name + "'\n"
                        + "}\n"
                        + "echo '" + name + " done'",
                true));
        return p.scheduleBuild2(0).waitForStart();
    }

    private static WorkflowRun startRunning(JenkinsRule j, String name, int quantity) throws Exception {
        WorkflowRun run = start(j, name, quantity);
        SemaphoreStep.waitForStart(name + "/1", run);
        return run;
    }

    private static WorkflowRun startWaiting(JenkinsRule j, String name, int quantity) throws Exception {
        WorkflowRun run = start(j, name, quantity);
        j.waitForMessage("is not free, waiting for execution", run);
        assertHolds(run, 0);
        return run;
    }

    private static void finish(JenkinsRule j, WorkflowRun run) throws Exception {
        SemaphoreStep.success(run.getParent().getName() + "/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(run));
        assertHolds(run, 0);
    }

    // ---------------------------------------------------------------------------
    /** First reported case: 9 resources, 4 + 4 running, third build asking 4 must get 4 (not 5). */
    @Test
    @Issue("890")
    void thirdBuildGetsRequestedQuantityAfterFirstReleases(JenkinsRule j) throws Exception {
        createResources(9);

        WorkflowRun b1 = startRunning(j, "b1", 4);
        assertHolds(b1, 4);
        WorkflowRun b2 = startRunning(j, "b2", 4);
        assertHolds(b2, 4);
        WorkflowRun b3 = startWaiting(j, "b3", 4);

        finish(j, b1);
        SemaphoreStep.waitForStart("b3/1", b3);
        assertHolds(b3, 4);
        assertHolds(b2, 4);

        finish(j, b2);
        assertHolds(b3, 4);
        finish(j, b3);
    }

    // ---------------------------------------------------------------------------
    /** Second reported case: quantity 1 builds queued behind others must get exactly 1. */
    @Test
    @Issue("890")
    void queuedQuantityOneBuildsGetOne(JenkinsRule j) throws Exception {
        createResources(5);

        WorkflowRun b1 = startRunning(j, "b1", 4);
        WorkflowRun b2 = startRunning(j, "b2", 1);
        WorkflowRun b3 = startWaiting(j, "b3", 4);
        WorkflowRun b4 = startWaiting(j, "b4", 1);
        WorkflowRun b5 = startWaiting(j, "b5", 1);

        // 4 free: b3 (4) is first in the queue and takes them all
        finish(j, b1);
        SemaphoreStep.waitForStart("b3/1", b3);
        assertHolds(b3, 4);
        assertHolds(b4, 0);
        assertHolds(b5, 0);

        // 1 free: b4 takes it, b5 keeps waiting
        finish(j, b2);
        SemaphoreStep.waitForStart("b4/1", b4);
        assertHolds(b4, 1);
        assertHolds(b5, 0);

        // 4 free: b5 must take only 1 of them
        finish(j, b3);
        SemaphoreStep.waitForStart("b5/1", b5);
        assertHolds(b5, 1);
        assertHolds(b4, 1);

        finish(j, b4);
        finish(j, b5);
    }

    // ---------------------------------------------------------------------------
    /** Third reported case: a build without quantity held everything, the next one asks for 1. */
    @Test
    @Issue("890")
    void quantityOneAfterLockAllGetsOne(JenkinsRule j) throws Exception {
        createResources(5);

        WorkflowRun b1 = startRunning(j, "b1", 0);
        assertHolds(b1, 5);
        WorkflowRun b2 = startWaiting(j, "b2", 1);

        finish(j, b1);
        SemaphoreStep.waitForStart("b2/1", b2);
        assertHolds(b2, 1);
        finish(j, b2);
    }

    // ---------------------------------------------------------------------------
    /** Lock-all holder, then several waiting builds with quantities 1 to 4 that all fit at once. */
    @Test
    @Issue("890")
    void severalWaitingBuildsWithDifferentQuantitiesAllFit(JenkinsRule j) throws Exception {
        createResources(10);

        WorkflowRun holder = startRunning(j, "holder", 0);
        assertHolds(holder, 10);
        int[] quantities = {3, 1, 4, 2};
        WorkflowRun[] waiting = new WorkflowRun[quantities.length];
        for (int i = 0; i < quantities.length; i++) {
            waiting[i] = startWaiting(j, "w" + i + "q" + quantities[i], quantities[i]);
        }

        finish(j, holder);
        for (int i = 0; i < quantities.length; i++) {
            SemaphoreStep.waitForStart(waiting[i].getParent().getName() + "/1", waiting[i]);
        }
        for (int i = 0; i < quantities.length; i++) {
            assertHolds(waiting[i], quantities[i]);
        }
        for (WorkflowRun run : waiting) {
            finish(j, run);
        }
    }

    // ---------------------------------------------------------------------------
    /**
     * Not everything fits: 8 resources, waiting builds ask 4, 3, 2, 1. After the holder releases,
     * 4 + 3 + 1 start and the 2 keeps waiting; each release then re-processes the queue.
     */
    @Test
    @Issue("890")
    void queueProcessedOnEachReleaseWithDifferentQuantities(JenkinsRule j) throws Exception {
        createResources(8);

        WorkflowRun holder = startRunning(j, "holder", 0);
        WorkflowRun q4 = startWaiting(j, "q4", 4);
        WorkflowRun q3 = startWaiting(j, "q3", 3);
        WorkflowRun q2 = startWaiting(j, "q2", 2);
        WorkflowRun q1 = startWaiting(j, "q1", 1);

        finish(j, holder);
        SemaphoreStep.waitForStart("q4/1", q4);
        SemaphoreStep.waitForStart("q3/1", q3);
        SemaphoreStep.waitForStart("q1/1", q1);
        assertHolds(q4, 4);
        assertHolds(q3, 3);
        assertHolds(q1, 1);
        assertHolds(q2, 0);

        // 1 free: not enough for q2
        finish(j, q1);
        assertHolds(q2, 0);

        // 4 free: q2 must take 2 of them
        finish(j, q3);
        SemaphoreStep.waitForStart("q2/1", q2);
        assertHolds(q2, 2);
        assertHolds(q4, 4);

        // a new quantity-1 build arriving now gets 1 directly
        WorkflowRun late = startRunning(j, "late", 1);
        assertHolds(late, 1);

        finish(j, q4);
        finish(j, q2);
        finish(j, late);
    }

    // ---------------------------------------------------------------------------
    /** Same queue, but the waiting builds are released one by one while others keep arriving. */
    @Test
    @Issue("890")
    void quantityBuildsQueuedBehindPartialHolders(JenkinsRule j) throws Exception {
        createResources(6);

        WorkflowRun a = startRunning(j, "a", 3);
        WorkflowRun b = startRunning(j, "b", 3);
        WorkflowRun c = startWaiting(j, "c", 1);
        WorkflowRun d = startWaiting(j, "d", 2);
        WorkflowRun e = startWaiting(j, "e", 1);

        // 3 free: c (1) and d (2) start, e waits
        finish(j, a);
        SemaphoreStep.waitForStart("c/1", c);
        SemaphoreStep.waitForStart("d/1", d);
        assertHolds(c, 1);
        assertHolds(d, 2);
        assertHolds(e, 0);

        // 3 free: e must take 1
        finish(j, b);
        SemaphoreStep.waitForStart("e/1", e);
        assertHolds(e, 1);

        finish(j, c);
        finish(j, d);
        finish(j, e);
    }

    // ===========================================================================
    // Several requirements in one lock() call (label + extra).
    // Without requireDistinctResources, README allows one entry's resources to satisfy another
    // entry, so exact distinct counts are only asserted with requireDistinctResources enabled.
    // ===========================================================================

    private static LockableResource addResource(String name, String group, String labels) {
        LockableResource resource = new LockableResource(name);
        resource.setGroup(group);
        resource.setLabelsFromString(labels);
        LockableResourcesManager.get().addResource(resource);
        return resource;
    }

    /** Starts a pipeline whose lock() arguments are given as is; it waits on semaphore {@code name}. */
    private static WorkflowRun startLock(JenkinsRule j, String name, String lockArgs) throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, name);
        p.setDefinition(new CpsFlowDefinition(
                "lock(" + lockArgs + ", variable: 'HELD') {\n"
                        + "  echo \"held=${env.HELD}\"\n"
                        + "  semaphore '" + name + "'\n"
                        + "}\n"
                        + "echo '" + name + " done'",
                true));
        return p.scheduleBuild2(0).waitForStart();
    }

    /**
     * Runs the lock and checks the build holds exactly {@code expected} distinct resources. Fails
     * (and aborts the build) instead of hanging when the lock is queued instead of acquired.
     */
    private static List<String> runLockAndCheck(JenkinsRule j, String name, String lockArgs, int expected)
            throws Exception {
        WorkflowRun run = startLock(j, name, lockArgs);
        long deadline = System.currentTimeMillis() + 120_000;
        while (true) {
            String log = JenkinsRule.getLog(run);
            if (log.contains("Lock acquired on")) {
                break;
            }
            if (log.contains("is not free, waiting for execution")) {
                run.doStop();
                j.waitForCompletion(run);
                throw new AssertionError(name + " was queued instead of locking " + expected + " resources:\n" + log);
            }
            assertTrue(System.currentTimeMillis() < deadline, name + " neither locked nor queued:\n" + log);
            Thread.sleep(100);
        }
        SemaphoreStep.waitForStart(name + "/1", run);
        List<String> held;
        try {
            held = assertHolds(run, expected);
            assertEquals(expected, new HashSet<>(held).size(), "held " + held);
        } finally {
            SemaphoreStep.success(name + "/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(run));
        }
        assertHolds(run, 0);
        return held;
    }

    // ---------------------------------------------------------------------------
    /** Label FOO quantity 2 + extra label FOO quantity 1, distinct mode: 3 different resources. */
    @Test
    @Issue("890")
    void extraSameLabelHoldsThreeDistinctWithDistinctMode(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().setRequireDistinctResources(true);
        createResources(5);
        runLockAndCheck(j, "extraSameD", "label: 'FOO', quantity: 2, extra: [[label: 'FOO', quantity: 1]]", 3);
    }

    // ---------------------------------------------------------------------------
    /**
     * Main label FOO quantity 2 + extra FOO && BAR quantity 1 (partial overlap), distinct mode: 3
     * different resources, one of them FOO && BAR. The FOO+BAR resources come first in the list, so a
     * greedy main selector would take them.
     */
    @Test
    @Issue("890")
    void extraPartialOverlapHoldsThreeDistinctWithDistinctMode(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().setRequireDistinctResources(true);
        addResource("fb_1", null, "FOO BAR");
        addResource("fb_2", null, "FOO BAR");
        addResource("f_3", null, "FOO");
        addResource("f_4", null, "FOO");
        addResource("f_5", null, "FOO");
        List<String> held = runLockAndCheck(
                j, "overlapD", "label: 'FOO', quantity: 2, extra: [[label: 'FOO && BAR', quantity: 1]]", 3);
        assertTrue(held.contains("fb_1") || held.contains("fb_2"), "a FOO && BAR resource must be held: " + held);
    }

    // ---------------------------------------------------------------------------
    /**
     * Main label without quantity (all) + extra FOO quantity 1. README: "By default, resources
     * selected by one entry may also satisfy another entry" and "An omitted or non-positive quantity
     * requires all matching resources", so the build holds all 5 FOO resources, each once.
     */
    @Test
    @Issue("890")
    void lockAllPlusExtraSameLabelHoldsAllOnce(JenkinsRule j) throws Exception {
        createResources(5);
        runLockAndCheck(j, "allPlusExtra", "label: 'FOO', extra: [[label: 'FOO', quantity: 1]]", 5);
    }

    /**
     * Same with requireDistinctResources. README: "Combining an 'all' selector with an overlapping
     * selector ... can never be satisfied while distinct matching is enabled", so the build waits and
     * holds nothing.
     */
    @Test
    @Issue("890")
    void lockAllPlusExtraSameLabelWaitsWithDistinctMode(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().setRequireDistinctResources(true);
        createResources(5);
        WorkflowRun run = startLock(j, "allPlusExtraD", "label: 'FOO', extra: [[label: 'FOO', quantity: 1]]");
        j.waitForMessage("is not free, waiting for execution", run);
        assertHolds(run, 0);
        run.doStop();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(run));
        assertHolds(run, 0);
    }

    // ===========================================================================
    // Case 5: freestyle and pipeline builds waiting for the same label
    // ===========================================================================

    /** Freestyle builder that signals when it runs and blocks until the test releases it. */
    public static class BlockingBuilder extends TestBuilder {
        static final Map<String, OneShotEvent> STARTED = new ConcurrentHashMap<>();
        static final Map<String, OneShotEvent> RELEASE = new ConcurrentHashMap<>();

        private final String id;

        public BlockingBuilder(String id) {
            this.id = id;
        }

        static OneShotEvent started(String id) {
            return STARTED.computeIfAbsent(id, k -> new OneShotEvent());
        }

        static OneShotEvent release(String id) {
            return RELEASE.computeIfAbsent(id, k -> new OneShotEvent());
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException {
            started(id).signal();
            release(id).block();
            return true;
        }
    }

    private static final class Freestyle {
        final String name;
        final QueueTaskFuture<FreeStyleBuild> future;

        Freestyle(String name, QueueTaskFuture<FreeStyleBuild> future) {
            this.name = name;
            this.future = future;
        }
    }

    private static Freestyle scheduleFreestyle(JenkinsRule j, String name, int quantity) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(name);
        p.addProperty(new RequiredResourcesProperty(null, null, String.valueOf(quantity), LABEL, null));
        p.getBuildersList().add(new BlockingBuilder(name));
        Freestyle fs = new Freestyle(name, p.scheduleBuild2(0));
        long deadline = System.currentTimeMillis() + 30_000;
        while (j.jenkins.getQueue().getItem(p) == null
                && !fs.future.getStartCondition().isDone()) {
            assertTrue(System.currentTimeMillis() < deadline, name + " never reached the queue");
            Thread.sleep(100);
        }
        return fs;
    }

    private static FreeStyleBuild awaitFreestyleRunning(Freestyle fs) throws Exception {
        FreeStyleBuild build = fs.future.getStartCondition().get(120, TimeUnit.SECONDS);
        assertNotNull(build);
        BlockingBuilder.started(fs.name).block(120_000);
        assertTrue(BlockingBuilder.started(fs.name).isSignaled(), fs.name + " never reached its builder");
        return build;
    }

    private static boolean isFreestyleRunning(Freestyle fs) {
        return BlockingBuilder.started(fs.name).isSignaled();
    }

    private static void finishFreestyle(JenkinsRule j, Freestyle fs) throws Exception {
        BlockingBuilder.release(fs.name).signal();
        FreeStyleBuild build = fs.future.get(120, TimeUnit.SECONDS);
        j.assertBuildStatusSuccess(build);
        assertHolds(build, 0);
    }

    /** Never more resources locked than exist (each resource has a single owner build). */
    private static void assertNotOverbooked(int total) {
        int locked = 0;
        for (LockableResource r : LockableResourcesManager.get().getResources()) {
            if (r.getBuild() != null) {
                locked++;
            }
        }
        assertTrue(locked <= total, "locked " + locked + " of " + total);
    }

    /** Waits until one of two candidates starts; returns true when it is the pipeline one. */
    private static boolean awaitEither(WorkflowRun pipeline, String semaphore, Freestyle fs) throws Exception {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            if (isFreestyleRunning(fs)) {
                return false;
            }
            if (!heldBy(pipeline).isEmpty()) {
                SemaphoreStep.waitForStart(semaphore + "/1", pipeline);
                return true;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("neither " + pipeline + " nor " + fs.name + " started");
    }

    /** Case 5: freestyle (label + quantity) and pipeline builds waiting for the same label. */
    @Test
    @Issue("890")
    void freestyleAndPipelineWaitingForSameLabel(JenkinsRule j) throws Exception {
        j.jenkins.setNumExecutors(6);
        createResources(6);

        // pipeline holding everything
        WorkflowRun holder = startRunning(j, "holder", 0);
        assertHolds(holder, 6);

        // round 1: 2 (freestyle) + 3 (pipeline) + 1 (freestyle) = 6 waiting
        Freestyle fsA = scheduleFreestyle(j, "fsA", 2);
        WorkflowRun pB = startWaiting(j, "pB", 3);
        Freestyle fsC = scheduleFreestyle(j, "fsC", 1);
        assertFalse(isFreestyleRunning(fsA));
        assertFalse(isFreestyleRunning(fsC));

        finish(j, holder);
        SemaphoreStep.waitForStart("pB/1", pB);
        FreeStyleBuild a = awaitFreestyleRunning(fsA);
        FreeStyleBuild c = awaitFreestyleRunning(fsC);
        assertHolds(pB, 3);
        assertHolds(a, 2);
        assertHolds(c, 1);
        assertNotOverbooked(6);

        // round 2: a pipeline and a freestyle both wait for 2
        WorkflowRun pD = startWaiting(j, "pD", 2);
        Freestyle fsE = scheduleFreestyle(j, "fsE", 2);
        assertFalse(isFreestyleRunning(fsE));

        // 3 free: exactly one of pD / fsE starts with 2
        finish(j, pB);
        boolean pipelineFirst = awaitEither(pD, "pD", fsE);
        if (pipelineFirst) {
            assertHolds(pD, 2);
        } else {
            assertHolds(awaitFreestyleRunning(fsE), 2);
        }
        assertNotOverbooked(6);

        // 1 + 1 free: the other one starts with 2
        finishFreestyle(j, fsC);
        if (pipelineFirst) {
            assertHolds(awaitFreestyleRunning(fsE), 2);
            assertHolds(pD, 2);
        } else {
            SemaphoreStep.waitForStart("pD/1", pD);
            assertHolds(pD, 2);
            assertHolds(fsE.future.getStartCondition().get(), 2);
        }
        assertHolds(a, 2);
        assertNotOverbooked(6);

        finishFreestyle(j, fsA);
        finish(j, pD);
        finishFreestyle(j, fsE);
    }
}
