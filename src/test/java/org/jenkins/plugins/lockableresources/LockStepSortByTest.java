package org.jenkins.plugins.lockableresources;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LockStepSortByTest extends LockStepTestBase {

    private static final String PREFER_NWCARD2 = "resourceLabels.contains('nwcard:2') ? 0 : 1";

    private static void createResources() {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("resource1", "nwcard:1");
        lrm.createResourceWithLabel("resource2", "nwcard:1 nwcard:2");
    }

    private static WorkflowRun runLock(JenkinsRule j, String lockArgs) throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(label: 'nwcard:1', quantity: 1, variable: 'LOCKED'" + lockArgs + ") {\n"
                        + "  echo \"Locked: ${env.LOCKED}\"\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        return b1;
    }

    @Test
    @Issue("669")
    void sortByPrefersMatchingResource(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = runLock(j, ", sortBy: \"" + PREFER_NWCARD2 + "\"");
        j.assertLogContains("Locked: resource2", b1);
    }

    @Test
    @Issue("669")
    void withoutSortByKeepsOrder(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = runLock(j, "");
        j.assertLogContains("Locked: resource1", b1);
    }

    @Test
    @Issue("669")
    void sortByWithRandomStrategy(JenkinsRule j) throws Exception {
        createResources();
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "for (int i = 0; i < 5; i++) {\n"
                        + "  lock(label: 'nwcard:1', quantity: 1, variable: 'LOCKED', resourceSelectStrategy: 'random',"
                        + " sortBy: \"" + PREFER_NWCARD2 + "\") {\n"
                        + "    echo \"Locked: ${env.LOCKED}\"\n"
                        + "  }\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogNotContains("Locked: resource1", b1);
        j.assertLogContains("Locked: resource2", b1);
    }

    @Test
    @Issue("669")
    void sortByAppliesWhenQueued(JenkinsRule j) throws Exception {
        createResources();
        WorkflowJob holder = j.jenkins.createProject(WorkflowJob.class, "holder");
        holder.setDefinition(new CpsFlowDefinition("""
                lock(label: 'nwcard:1', quantity: 2) {
                  semaphore 'wait-inside'
                }
                echo 'Finish'""", true));
        WorkflowRun b1 = holder.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(label: 'nwcard:1', quantity: 1, variable: 'LOCKED', sortBy: \"" + PREFER_NWCARD2 + "\") {\n"
                        + "  echo \"Locked: ${env.LOCKED}\"\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution ...", b2);

        SemaphoreStep.success("wait-inside/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        j.assertLogContains("Locked: resource2", b2);
    }

    @Test
    @Issue("669")
    void throwingScriptFallsBack(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = runLock(j, ", sortBy: \"1 / 0\"");
        j.assertLogContains("WARNING: sortBy script failed, keeping default resource order", b1);
        j.assertLogContains("Locked: resource1", b1);
    }

    @Test
    @Issue("669")
    void nonNumericScriptFallsBack(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = runLock(j, ", sortBy: \"'not a number'\"");
        j.assertLogContains("WARNING: sortBy script failed, keeping default resource order", b1);
        j.assertLogContains("must return a number", b1);
        j.assertLogContains("Locked: resource1", b1);
    }

    @Test
    @Issue("669")
    void sortByRunsInSandbox(JenkinsRule j) throws Exception {
        createResources();
        // without the sandbox this would pick resource2
        WorkflowRun b1 = runLock(
                j, ", sortBy: \"jenkins.model.Jenkins.get() != null && resourceLabels.contains('nwcard:2') ? 0 : 1\"");
        j.assertLogContains("WARNING: sortBy script failed, keeping default resource order", b1);
        j.assertLogContains("RejectedAccessException", b1);
        j.assertLogContains("Locked: resource1", b1);
    }

    @Test
    @Issue("669")
    void sortByWithDistinctResources(JenkinsRule j) throws Exception {
        createResources();
        LockableResourcesManager.get().setRequireDistinctResources(true);
        WorkflowRun b1 = runLock(j, ", sortBy: \"" + PREFER_NWCARD2 + "\"");
        j.assertLogContains("Locked: resource2", b1);
    }
}
