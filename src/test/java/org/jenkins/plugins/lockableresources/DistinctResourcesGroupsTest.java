/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.Result;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Resource groups (#1100) combined with {@code requireDistinctResources}. Every case runs once with the
 * distinct matcher off (the regular {@code getAvailableResources} path) and once with it on, so a failure
 * that only shows up with {@code true} is attributable to {@code getDistinctAvailableResources}.
 *
 * <p>Setup: {@code g1-dev} (group g1, label x), {@code g2-dev} (group g2, label x) and {@code r1} (group g2,
 * no label). Nothing lives in the common group with label x.
 *
 * <p>Every method gets a hard limit so a build that waits on a lock forever (or a {@code waitForMessage} that
 * never matches) fails the test instead of blocking the whole run. Generous enough for Jenkins startup.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@WithJenkins
class DistinctResourcesGroupsTest extends LockStepTestBase {

    /** Upper bound for a build that is expected to finish. Beyond this we treat it as hung. */
    private static final long BUILD_LIMIT_MS = 30_000;

    private static LockableResourcesManager setup(boolean distinct) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        add("g1-dev", "g1", "x");
        add("g2-dev", "g2", "x");
        add("r1", "g2", "");
        manager.setRequireDistinctResources(distinct);
        return manager;
    }

    private static LockableResource add(String name, String group, String labels) {
        LockableResource resource = new LockableResource(name);
        resource.setGroup(group);
        resource.setLabelsFromString(labels);
        LockableResourcesManager.get().addResource(resource);
        return resource;
    }

    /** Mirrors what {@link LockStepExecution} builds for one {@code lock(...)} selector. */
    private static LockableResourcesStruct selector(String resource, String label, int quantity, String group) {
        LockableResourcesStruct requirement =
                new LockableResourcesStruct(resource == null ? List.of() : List.of(resource), label, quantity);
        requirement.setGroup(group);
        requirement.setGroupOnly(resource == null && label == null);
        return requirement;
    }

    private static List<String> names(List<LockableResource> resources) {
        return resources == null ? null : LockableResourcesManager.getResourcesNames(resources);
    }

    /** Waits a bounded time; kills the build and fails the test if it is still running. */
    private static WorkflowRun waitBounded(JenkinsRule j, WorkflowRun run) throws Exception {
        long deadline = System.currentTimeMillis() + BUILD_LIMIT_MS;
        while (run.isBuilding() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        if (run.isBuilding()) {
            run.doKill();
            j.waitForCompletion(run);
            fail("Build did not finish within " + BUILD_LIMIT_MS + " ms (hung waiting for the lock):\n" + run.getLog());
        }
        return run;
    }

    // ---- case 1: lock(group: 'g1', quantity: 1) -------------------------------------------------

    @ParameterizedTest(name = "requireDistinctResources={0}")
    @ValueSource(booleans = {false, true})
    void groupOnlySelectorResolvesViaManager(boolean distinct, JenkinsRule j) {
        LockableResourcesManager manager = setup(distinct);
        assertEquals(
                List.of("g1-dev"),
                names(manager.getAvailableResources(List.of(selector(null, null, 1, "g1")))),
                "group-only selector must pick the (single) free resource of g1");
    }

    @ParameterizedTest(name = "requireDistinctResources={0}")
    @ValueSource(booleans = {false, true})
    void groupOnlyLockDoesNotHang(boolean distinct, JenkinsRule j) throws Exception {
        LockableResourcesManager manager = setup(distinct);
        WorkflowJob job = j.createProject(WorkflowJob.class, "group-only-" + distinct);
        job.setDefinition(new CpsFlowDefinition("""
                lock(group: 'g1', quantity: 1, variable: 'DEV') {
                  echo "selected=${env.DEV}"
                }
                """, true));
        WorkflowRun run = waitBounded(j, job.scheduleBuild2(0).waitForStart());
        j.assertBuildStatusSuccess(run);
        j.assertLogContains("selected=g1-dev", run);
        j.assertLogNotContains("No resource selector in requirement", run);
        assertTrue(manager.fromName("g1-dev").isFree());
    }

    // ---- case 2: lock(label: 'x', group: 'g1') --------------------------------------------------

    @ParameterizedTest(name = "requireDistinctResources={0}")
    @ValueSource(booleans = {false, true})
    void labelInGroupNeverLeaksAnotherGroupViaManager(boolean distinct, JenkinsRule j) {
        LockableResourcesManager manager = setup(distinct);
        // g1-dev is busy; the only remaining 'x' is g2-dev, which must NOT be eligible for group g1.
        manager.reserve(List.of(manager.fromName("g1-dev")), "test");
        assertNull(
                names(manager.getAvailableResources(List.of(selector(null, "x", 1, "g1")))),
                "label x in group g1 must not be satisfied by g2-dev");
        manager.unreserve(List.of(manager.fromName("g1-dev")));
        assertEquals(List.of("g1-dev"), names(manager.getAvailableResources(List.of(selector(null, "x", 1, "g1")))));
    }

    @ParameterizedTest(name = "requireDistinctResources={0}")
    @ValueSource(booleans = {false, true})
    void labelInGroupWaitsInsteadOfTakingOtherGroup(boolean distinct, JenkinsRule j) throws Exception {
        LockableResourcesManager manager = setup(distinct);
        LockableResource g1 = manager.fromName("g1-dev");
        LockableResource g2 = manager.fromName("g2-dev");
        manager.reserve(List.of(g1), "test");
        WorkflowJob job = j.createProject(WorkflowJob.class, "label-group-" + distinct);
        job.setDefinition(new CpsFlowDefinition("""
                lock(label: 'x', group: 'g1', quantity: 1, variable: 'DEV') {
                  echo "selected=${env.DEV}"
                }
                """, true));
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        // While g1-dev is reserved the build must wait, not grab g2-dev.
        // "is not free, waiting for execution" is printed by onLockFailed on both selector paths; the
        // per-resource "is reserved" cause is only logged by the non-distinct path, so do not wait on it.
        j.waitForMessage("is not free, waiting for execution", run);
        isPaused(run, 1, 1);
        assertTrue(run.isBuilding(), "build must still be waiting for g1-dev");
        assertTrue(g2.isFree(), "g2-dev (group g2) must not be taken for a group g1 request");
        j.assertLogNotContains("selected=g2-dev", run);
        manager.unreserve(List.of(g1));
        waitBounded(j, run);
        j.assertBuildStatusSuccess(run);
        j.assertLogContains("selected=g1-dev", run);
    }

    // ---- case 3: lock(resource: 'r1', group: 'g1') with r1 in g2 ----------------------------------

    @ParameterizedTest(name = "requireDistinctResources={0}")
    @ValueSource(booleans = {false, true})
    void namedResourceOutsideGroupIsRejectedViaManager(boolean distinct, JenkinsRule j) {
        LockableResourcesManager manager = setup(distinct);
        assertNull(
                names(manager.getAvailableResources(List.of(selector("r1", null, 0, "g1")))),
                "r1 belongs to g2 and must not be handed out for a g1 request");
        assertTrue(manager.fromName("r1").isFree());
        // Sanity: the same name in its own group is fine.
        assertEquals(List.of("r1"), names(manager.getAvailableResources(List.of(selector("r1", null, 0, "g2")))));
    }

    @ParameterizedTest(name = "requireDistinctResources={0}")
    @ValueSource(booleans = {false, true})
    void namedResourceOutsideGroupFailsTheBuild(boolean distinct, JenkinsRule j) throws Exception {
        LockableResourcesManager manager = setup(distinct);
        WorkflowJob job = j.createProject(WorkflowJob.class, "named-group-" + distinct);
        job.setDefinition(
                new CpsFlowDefinition("lock(resource: 'r1', group: 'g1') { error 'body must not run' }", true));
        WorkflowRun run = waitBounded(j, job.scheduleBuild2(0).waitForStart());
        j.assertBuildStatus(Result.FAILURE, run);
        j.assertLogContains("not requested group", run);
        j.assertLogNotContains("body must not run", run);
        assertTrue(manager.fromName("r1").isFree());
    }
}
