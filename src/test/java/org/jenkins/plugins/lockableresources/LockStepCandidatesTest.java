package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkins.plugins.lockableresources.remote.RemoteServerFixture;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.Snippetizer;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LockStepCandidatesTest extends LockStepTestBase {

    private static final String WAITING = "is not free, waiting for execution";

    private static LockableResourcesManager createResources() {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("c1", "card");
        lrm.createResourceWithLabel("c2", "card");
        lrm.createResourceWithLabel("c3", "card");
        lrm.createResourceWithLabel("board1", "board");
        return lrm;
    }

    private static WorkflowRun startLock(JenkinsRule j, String name, String lockArgs) throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, name);
        p.setDefinition(new CpsFlowDefinition(
                "lock(variable: 'LOCKED', " + lockArgs + ") {\n"
                        + "  echo \"Locked: ${env.LOCKED}\"\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        return p.scheduleBuild2(0).waitForStart();
    }

    private static WorkflowRun runLock(JenkinsRule j, String lockArgs) throws Exception {
        WorkflowRun b1 = startLock(j, "p", lockArgs);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        return b1;
    }

    private static WorkflowRun runFailingLock(JenkinsRule j, String lockArgs) throws Exception {
        WorkflowRun b1 = startLock(j, "p", lockArgs);
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b1));
        return b1;
    }

    private static WorkflowRun holdLock(JenkinsRule j, String lockArgs) throws Exception {
        WorkflowJob holder = j.jenkins.createProject(WorkflowJob.class, "holder");
        holder.setDefinition(new CpsFlowDefinition(
                "lock(" + lockArgs + ") {\n" + "  semaphore 'wait-inside'\n" + "}\n" + "echo 'Finish'", true));
        WorkflowRun b1 = holder.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);
        return b1;
    }

    private static void release(JenkinsRule j, WorkflowRun holder) throws Exception {
        SemaphoreStep.success("wait-inside/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(holder));
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }

    // -------------------------------------------------------------------------
    // LockStep round trip

    @Test
    @Issue("669")
    void candidatesRoundTrip(JenkinsRule j) throws Exception {
        DescribableModel<LockStep> model = DescribableModel.of(LockStep.class);
        LockStep step = model.instantiate(Map.of("candidates", List.of("c3", "c2"), "quantity", 1));
        assertEquals(List.of("c3", "c2"), step.candidates);

        // LockStep exposes public fields without getters, so JenkinsRule.assertEqualDataBoundBeans cannot be used
        UninstantiatedDescribable ud = model.uninstantiate2(step);
        assertEquals(List.of("c3", "c2"), ud.getArguments().get("candidates"));
        LockStep copy = ud.instantiate(LockStep.class);
        assertEquals(step.candidates, copy.candidates);
        assertEquals(step.quantity, copy.quantity);
        assertEquals(ud.getArguments(), model.uninstantiate2(copy).getArguments());

        String snippet = Snippetizer.object2Groovy(step);
        assertTrue(snippet.contains("candidates: ['c3', 'c2']"), snippet);
        assertEquals("Candidates: [c3, c2], Quantity: 1", step.toString());
    }

    @Test
    @Issue("669")
    void extraCandidatesRoundTrip(JenkinsRule j) throws Exception {
        DescribableModel<LockStep> model = DescribableModel.of(LockStep.class);
        LockStep step = model.instantiate(
                Map.of("label", "board", "extra", List.of(Map.of("candidates", List.of(" c2 ", "c1"), "quantity", 1))));
        assertEquals(List.of("c2", "c1"), step.extra.get(0).candidates);
        LockStep copy = model.uninstantiate2(step).instantiate(LockStep.class);
        assertEquals(List.of("c2", "c1"), copy.extra.get(0).candidates);
        assertEquals("{Label: board},{Candidates: [c2, c1], Quantity: 1}", copy.toString());
    }

    @Test
    @Issue("669")
    void candidatesAreTrimmedAndBlankCandidatesAreDropped(JenkinsRule j) throws Exception {
        DescribableModel<LockStep> model = DescribableModel.of(LockStep.class);
        LockStep trimmed = model.instantiate(Map.of("candidates", List.of(" c3 ", "  ", "")));
        assertEquals(List.of("c3"), trimmed.candidates);

        for (List<String> blank : List.of(List.<String>of(), List.of(" "))) {
            LockStep step = model.instantiate(Map.of("label", "card", "candidates", blank));
            assertNull(step.candidates);
            assertFalse(model.uninstantiate2(step).getArguments().containsKey("candidates"));
            assertFalse(Snippetizer.object2Groovy(step).contains("candidates"));
        }
    }

    // -------------------------------------------------------------------------
    // with a quantity

    @Test
    @Issue("669")
    void firstCandidateInListOrderIsLocked(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = runLock(j, "candidates: ['c3', 'c1', 'c2'], quantity: 2");
        j.assertLogContains("Locked: c3,c1", b1);
    }

    @Test
    @Issue("669")
    void busyFirstCandidateFallsThroughToNext(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun holder = holdLock(j, "'c3'");
        WorkflowRun b1 = runLock(j, "candidates: ['c3', 'c1', 'c2'], quantity: 1");
        j.assertLogContains("Locked: c1", b1);
        release(j, holder);
    }

    @Test
    @Issue("669")
    void missingCandidatesAreSkippedAndLoggedOnce(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = createResources();
        WorkflowRun holder = holdLock(j, "'c2'");
        WorkflowRun b1 = startLock(j, "p", "candidates: ['nope1', 'c2', 'nope2'], quantity: 1");
        j.waitForMessage(WAITING, b1);
        release(j, holder);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogContains("Locked: c2", b1);
        String log = JenkinsRule.getLog(b1);
        assertEquals(1, count(log, "Candidates that do not exist are skipped: [nope1, nope2]"), log);
        assertNull(lrm.fromName("nope1"), "a candidate with a quantity is never created");
        assertNull(lrm.fromName("nope2"));
    }

    @Test
    @Issue("669")
    void fewerCandidatesThanQuantityFailsWhenFailFastIsOn(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = createResources();
        lrm.setFailFastOnInsufficientResources(true);
        WorkflowRun b1 = runFailingLock(j, "candidates: ['c1', 'nope'], quantity: 2");
        j.assertLogContains("Requested 2 resource(s) from candidates [c1, nope], but only 1 of them exist.", b1);
    }

    @Test
    @Issue("669")
    void fewerCandidatesThanQuantityWaitsWhenFailFastIsOff(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = createResources();
        lrm.setFailFastOnInsufficientResources(false);
        WorkflowRun b1 = startLock(j, "p", "candidates: ['c1', 'later'], quantity: 2");
        j.waitForMessage(WAITING, b1);
        assertTrue(b1.isBuilding());

        // the queued request keeps the names, so a resource created later becomes a candidate
        lrm.createResourceWithLabel("later", "card");
        LockableResourcesManager.scheduleQueueMaintenance();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogContains("Locked: c1,later", b1);
    }

    @Test
    @Issue("669")
    void groupLimitsCandidates(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = createResources();
        lrm.setResourceGroups(List.of(new ResourceGroup("plc")));
        lrm.fromName("c2").setGroup("plc");
        lrm.fromName("c3").setGroup("plc");

        WorkflowRun b1 = runLock(j, "candidates: ['c1', 'c3', 'c2'], quantity: 1, group: 'plc'");
        j.assertLogContains("Locked: c3", b1);
        b1.getParent().delete();

        // without a group only common resources count
        WorkflowRun b2 = runLock(j, "candidates: ['c3', 'c2', 'c1'], quantity: 1");
        j.assertLogContains("Locked: c1", b2);
        b2.getParent().delete();

        lrm.setFailFastOnInsufficientResources(true);
        WorkflowRun b3 = runFailingLock(j, "candidates: ['c1', 'c3', 'c2'], quantity: 3, group: 'plc'");
        j.assertLogContains(
                "Requested 3 resource(s) from candidates [c1, c3, c2] in group plc, but only 2 of them exist.", b3);
    }

    @Test
    @Issue("669")
    void randomShufflesCandidates(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = createResources();
        LockableResourcesStruct struct = LockableResourcesStruct.forCandidates(List.of("c1", "c2", "c3"), 1);
        Set<String> sequential = new HashSet<>();
        Set<String> random = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            sequential.add(lrm.getAvailableResources(List.of(struct), null, ResourceSelectStrategy.SEQUENTIAL)
                    .get(0)
                    .getName());
            random.add(lrm.getAvailableResources(List.of(struct), null, ResourceSelectStrategy.RANDOM)
                    .get(0)
                    .getName());
        }
        assertEquals(Set.of("c1"), sequential);
        assertTrue(random.size() > 1, "random picked only " + random);

        WorkflowRun b1 = runLock(j, "candidates: ['c1', 'c2', 'c3'], quantity: 3, resourceSelectStrategy: 'random'");
        j.assertLogContains("Locked: c", b1);
    }

    @Test
    @Issue("669")
    void candidatesWithRequireDistinctResources(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = createResources();
        lrm.setRequireDistinctResources(true);
        // c1 is first for both entries: the matching gives the main entry c1 and the extra entry c3
        WorkflowRun b1 =
                runLock(j, "candidates: ['c1', 'c2'], quantity: 1, extra: [[candidates: ['c1', 'c3'], quantity: 1]]");
        j.assertLogContains("Locked: c1,c3", b1);
        b1.getParent().delete();

        // a busy candidate is still skipped
        WorkflowRun holder = holdLock(j, "'c1'");
        WorkflowRun b2 = runLock(j, "candidates: ['c1', 'c2'], quantity: 1, extra: [[label: 'board', quantity: 1]]");
        j.assertLogContains("Locked: c2,board1", b2);
        release(j, holder);
    }

    @Test
    @Issue("669")
    void candidatesInExtraEntry(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = runLock(
                j,
                "label: 'card', quantity: 1, extra: [[candidates: ['c1', 'c2'], quantity: 1],"
                        + " [candidates: ['board1']]]");
        // the label takes c1, so the extra entry falls through to c2; no quantity locks all listed
        j.assertLogContains("Locked: c1,c2,board1", b1);
    }

    @Test
    @Issue("669")
    void deletedCandidateDropsOutWhileQueued(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = createResources();
        WorkflowRun holder = holdLock(j, "'c1'");
        LockableResource c2 = lrm.fromName("c2");
        assertTrue(lrm.reserve(List.of(c2), "someone"));

        WorkflowRun b1 = startLock(j, "p", "candidates: ['c2', 'c1'], quantity: 1");
        j.waitForMessage(WAITING, b1);

        // delete c2 while waiting, then free the old instance: it must not be locked any more
        lrm.removeResources(List.of(c2));
        lrm.unreserve(List.of(c2));
        assertNull(lrm.fromName("c2"));
        Thread.sleep(1000);
        assertTrue(b1.isBuilding());
        j.assertLogNotContains("Locked:", b1);

        release(j, holder);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogContains("Locked: c1", b1);
        assertNull(lrm.fromName("c2"));
    }

    // -------------------------------------------------------------------------
    // without a quantity

    @Test
    @Issue("669")
    void noQuantityLocksAllCandidates(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = runLock(j, "candidates: ['c3', 'c1']");
        j.assertLogContains("Locked: c3,c1", b1);
    }

    @Test
    @Issue("669")
    void noQuantityCreatesMissingCandidateWhenEphemeralIsOn(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = createResources();
        lrm.setAllowEphemeralResources(true);
        WorkflowRun b1 = runLock(j, "candidates: ['c1', 'eph']");
        j.assertLogContains("Resource [eph] did not exist. Created.", b1);
        j.assertLogContains("Locked: c1,eph", b1);
        assertNull(lrm.fromName("eph"), "the ephemeral resource is removed after the lock");
    }

    @Test
    @Issue("669")
    void noQuantitySkipsMissingCandidateWhenEphemeralIsOff(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = createResources();
        lrm.setAllowEphemeralResources(false);
        WorkflowRun b1 = runLock(j, "candidates: ['c1', 'eph1', 'c2', 'eph2']");
        j.assertLogContains("Candidate [eph1] does not exist and ephemeral resources are disabled. Skipped.", b1);
        j.assertLogContains("Candidate [eph2] does not exist and ephemeral resources are disabled. Skipped.", b1);
        j.assertLogNotContains("did not exist. Created.", b1);
        j.assertLogContains("Locked: c1,c2", b1);
        assertNull(lrm.fromName("eph1"));
    }

    @Test
    @Issue("669")
    void noQuantityWithAllCandidatesMissingDoesNotWaitForever(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = createResources();
        lrm.setAllowEphemeralResources(false);
        WorkflowRun b1 = startLock(j, "p", "candidates: ['eph1', 'eph2']");
        long deadline = System.currentTimeMillis() + 15_000;
        while (b1.isBuilding() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        boolean stillWaiting = b1.isBuilding();
        if (stillWaiting) {
            b1.doStop();
        }
        j.waitForCompletion(b1);
        j.assertLogContains("Candidate [eph2] does not exist and ephemeral resources are disabled. Skipped.", b1);
        assertFalse(stillWaiting, "the build waits in the queue with nothing to lock instead of failing");
        j.assertBuildStatus(Result.FAILURE, b1);
        j.assertLogContains("None of the candidates [eph1, eph2] exist and ephemeral resources are disabled.", b1);
        assertTrue(lrm.getCurrentQueuedContext().isEmpty());
    }

    // -------------------------------------------------------------------------
    // validation and remote

    @Test
    @Issue("669")
    void resourceAndCandidatesIsAnError(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = runFailingLock(j, "resource: 'c1', candidates: ['c2']");
        j.assertLogContains("candidates cannot be combined with resource or label.", b1);
        j.assertLogContains("Use exactly one of resource, label or candidates.", b1);
    }

    @Test
    @Issue("669")
    void labelAndCandidatesIsAnError(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = runFailingLock(j, "label: 'card', candidates: ['c2'], quantity: 1");
        j.assertLogContains("candidates cannot be combined with resource or label.", b1);
        b1.getParent().delete();

        WorkflowRun b2 = runFailingLock(j, "label: 'board', extra: [[label: 'card', candidates: ['c2']]]");
        j.assertLogContains("candidates cannot be combined with resource or label.", b2);
    }

    @Test
    @Issue("669")
    void shareIdAndCandidatesIsAnError(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = runFailingLock(j, "candidates: ['c1', 'c2'], shareId: 'x'");
        j.assertLogContains("The 'shareId' parameter cannot be combined with 'candidates'.", b1);
        j.assertLogNotContains("Locked:", b1);
        b1.getParent().delete();

        // an extra entry with candidates and a group gets the same message, not the group-only one
        WorkflowRun b2 =
                runFailingLock(j, "resource: 'board1', shareId: 'x', extra: [[candidates: ['c1'], group: 'g']]");
        j.assertLogContains("The 'shareId' parameter cannot be combined with 'candidates'.", b2);
        j.assertLogNotContains("Locked:", b2);
    }

    @Test
    @Issue("669")
    void candidatesFailForRemoteLocks(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        try {
            LockableResourcesManager.get().setRemotes(List.of(new RemoteConnection("server-a", remote.baseUrl(), "")));
            WorkflowRun b1 = runFailingLock(j, "candidates: ['board1'], quantity: 1, serverId: 'server-a'");
            j.assertLogContains("candidates is not supported for remote locks.", b1);
            j.assertLogNotContains("Locked:", b1);
            assertEquals(0, remote.acquireRequests.get());
            b1.getParent().delete();

            WorkflowRun b2 = runFailingLock(
                    j, "label: 'board', serverId: 'server-a', extra: [[candidates: ['board1'], quantity: 1]]");
            j.assertLogContains("candidates is not supported for remote locks.", b2);
            assertEquals(0, remote.acquireRequests.get());
        } finally {
            remote.stop();
        }
    }
}
