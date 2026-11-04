package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
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
class LockStepOrderTest extends LockStepTestBase {

    private static void createResources() {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("resource1", "nwcard:1");
        lrm.createResourceWithLabel("resource2", "nwcard:1 nwcard:2");
        lrm.createResourceWithLabel("resource3", "nwcard:1");
        lrm.createResourceWithLabel("other", "misc");
    }

    private static WorkflowRun runLock(JenkinsRule j, String lockArgs) throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(variable: 'LOCKED', " + lockArgs + ") {\n"
                        + "  echo \"Locked: ${env.LOCKED}\"\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
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

    // -------------------------------------------------------------------------
    // LockStep round trip

    @Test
    @Issue("669")
    void orderRoundTrip(JenkinsRule j) throws Exception {
        DescribableModel<LockStep> model = DescribableModel.of(LockStep.class);
        LockStep step = model.instantiate(
                Map.of("label", "nwcard:1", "quantity", 1, "order", List.of("resource3", "resource2")));
        assertEquals(List.of("resource3", "resource2"), step.order);

        // LockStep exposes public fields without getters, so JenkinsRule.assertEqualDataBoundBeans cannot be used
        UninstantiatedDescribable ud = model.uninstantiate2(step);
        assertEquals(List.of("resource3", "resource2"), ud.getArguments().get("order"));
        LockStep copy = ud.instantiate(LockStep.class);
        assertEquals(step.order, copy.order);
        assertEquals(step.label, copy.label);
        assertEquals(step.quantity, copy.quantity);
        assertEquals(ud.getArguments(), model.uninstantiate2(copy).getArguments());

        String snippet = Snippetizer.object2Groovy(step);
        assertTrue(snippet.contains("order: ['resource3', 'resource2']"), snippet);
    }

    @Test
    @Issue("669")
    void orderEntriesAreTrimmedAndBlankOrderIsDropped(JenkinsRule j) throws Exception {
        DescribableModel<LockStep> model = DescribableModel.of(LockStep.class);
        LockStep trimmed = model.instantiate(Map.of("label", "nwcard:1", "order", List.of(" resource3 ", "  ")));
        assertEquals(List.of("resource3"), trimmed.order);

        for (List<String> blank : List.of(List.<String>of(), List.of(" "))) {
            LockStep step = model.instantiate(Map.of("label", "nwcard:1", "order", blank));
            assertNull(step.order);
            assertFalse(model.uninstantiate2(step).getArguments().containsKey("order"));
            assertFalse(Snippetizer.object2Groovy(step).contains("order"));
        }
    }

    // -------------------------------------------------------------------------
    // lock behaviour

    @Test
    @Issue("669")
    void listedResourceIsPreferred(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = runLock(j, "label: 'nwcard:1', quantity: 1, order: ['resource3', 'resource2']");
        j.assertLogContains("Locked: resource3", b1);
    }

    @Test
    @Issue("669")
    void busyListedResourceFallsBackToNextListed(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun holder = holdLock(j, "'resource3'");
        WorkflowRun b1 = runLock(j, "label: 'nwcard:1', quantity: 1, order: ['resource3', 'resource2']");
        j.assertLogContains("Locked: resource2", b1);
        release(j, holder);
    }

    @Test
    @Issue("669")
    void unlistedCandidatesComeAfterListed(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = runLock(j, "label: 'nwcard:1', quantity: 2, order: ['resource3']");
        j.assertLogContains("Locked: resource3,resource1", b1);
    }

    @Test
    @Issue("669")
    void duplicateNamesCountAtFirstPosition(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = runLock(j, "label: 'nwcard:1', quantity: 2, order: ['resource3', 'resource2', 'resource3']");
        j.assertLogContains("Locked: resource3,resource2", b1);
    }

    @Test
    @Issue("669")
    void nonMatchingAndUnknownNamesAreIgnored(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = runLock(j, "label: 'nwcard:1', quantity: 3, order: ['missing', 'other', 'resource2']");
        j.assertLogContains("Locked: resource2,resource1,resource3", b1);
        assertNull(LockableResourcesManager.get().fromName("missing"));
    }

    @Test
    @Issue("669")
    void orderWithRandomStrategy(JenkinsRule j) throws Exception {
        createResources();
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                for (int i = 0; i < 5; i++) {
                  lock(label: 'nwcard:1', quantity: 1, variable: 'LOCKED', resourceSelectStrategy: 'random',
                       order: ['resource2']) {
                    echo "Locked: ${env.LOCKED}"
                  }
                }
                echo 'Finish'""", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogNotContains("Locked: resource1", b1);
        j.assertLogNotContains("Locked: resource3", b1);
        j.assertLogContains("Locked: resource2", b1);
    }

    @Test
    @Issue("669")
    void orderAppliesWhenQueued(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun holder = holdLock(j, "label: 'nwcard:1', quantity: 3");

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                lock(label: 'nwcard:1', quantity: 1, variable: 'LOCKED', order: ['resource2']) {
                  echo "Locked: ${env.LOCKED}"
                }
                echo 'Finish'""", true));
        WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution ...", b2);

        release(j, holder);
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        j.assertLogContains("Locked: resource2", b2);
    }

    @Test
    @Issue("669")
    void orderWithDistinctResources(JenkinsRule j) throws Exception {
        createResources();
        LockableResourcesManager.get().setRequireDistinctResources(true);
        WorkflowRun b1 = runLock(j, "label: 'nwcard:1', quantity: 2, order: ['resource3']");
        j.assertLogContains("Locked: resource3,resource1", b1);
    }

    @Test
    @Issue("669")
    void orderAppliesToExtraEntries(JenkinsRule j) throws Exception {
        createResources();
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("board1", "board");
        lrm.createResourceWithLabel("board2", "board");
        WorkflowRun b1 = runLock(
                j,
                "label: 'nwcard:1', quantity: 1, order: ['resource3', 'board2'],"
                        + " extra: [[label: 'board', quantity: 1]]");
        j.assertLogContains("Locked: resource3,board2", b1);
    }

    @Test
    @Issue("669")
    void namedResourceIgnoresOrder(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = runLock(j, "resource: 'resource1', order: ['resource2']");
        j.assertLogContains("Locked: resource1", b1);
        j.assertLogNotContains("Locked: resource1,", b1);
    }

    @Test
    @Issue("669")
    void emptyOrMissingOrderKeepsDefaultOrder(JenkinsRule j) throws Exception {
        createResources();
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                lock(label: 'nwcard:1', quantity: 2, variable: 'LOCKED') {
                  echo "Without order: ${env.LOCKED}"
                }
                lock(label: 'nwcard:1', quantity: 2, variable: 'LOCKED', order: []) {
                  echo "Empty order: ${env.LOCKED}"
                }
                echo 'Finish'""", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogContains("Without order: resource1,resource2", b1);
        j.assertLogContains("Empty order: resource1,resource2", b1);
    }

    @Test
    @Issue("669")
    void orderIsIgnoredForRemoteLocks(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        try {
            LockableResourcesManager.get().setRemotes(List.of(new RemoteConnection("server-a", remote.baseUrl(), "")));

            WorkflowJob job = j.createProject(WorkflowJob.class, "remote-order");
            job.setDefinition(new CpsFlowDefinition("""
                    lock(label: 'board', quantity: 1, serverId: 'server-a', order: ['board2']) {
                      echo 'inside'
                    }
                    """, true));
            WorkflowRun run = j.buildAndAssertSuccess(job);

            j.assertLogContains("order is not supported for remote locks and will be ignored.", run);
            j.assertLogContains("inside", run);
            assertEquals(1, remote.acquireRequests.get());
            assertFalse(remote.lastAcquireRawBody.get().contains("order"), remote.lastAcquireRawBody.get());
        } finally {
            remote.stop();
        }
    }
}
