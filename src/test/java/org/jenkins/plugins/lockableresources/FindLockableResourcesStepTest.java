package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class FindLockableResourcesStepTest {

    private static void createResources() {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("resource1", "nwcard:1");
        lrm.createResourceWithLabel("resource2", "nwcard:1 nwcard:2");
        lrm.createResourceWithLabel("other", "misc");
        LockableResource resource2 = lrm.fromName("resource2");
        resource2.setDescription("second card");
        resource2.setNote("spare");
    }

    private static WorkflowRun run(JenkinsRule j, String script) throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        return j.waitForCompletion(b1);
    }

    @Test
    @Issue("669")
    void returnsNameLabelsDescriptionAndNote(JenkinsRule j) throws Exception {
        createResources();

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("name", "resource2");
        expected.put("labels", List.of("nwcard:1", "nwcard:2"));
        expected.put("description", "second card");
        expected.put("note", "spare");
        assertEquals(List.of(expected), FindLockableResourcesStep.find("nwcard:2"));

        WorkflowRun b1 = run(j, """
                def found = findLockableResources(label: 'nwcard:2')
                echo "size=${found.size()}"
                echo "keys=${found[0].keySet()}"
                echo "found=${found[0].name}|${found[0].labels}|${found[0].description}|${found[0].note}"
                """);
        j.assertBuildStatusSuccess(b1);
        j.assertLogContains("size=1", b1);
        j.assertLogContains("keys=[name, labels, description, note]", b1);
        j.assertLogContains("found=resource2|[nwcard:1, nwcard:2]|second card|spare", b1);
    }

    @Test
    @Issue("669")
    void supportsLabelExpressions(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = run(j, """
                echo "and=${findLockableResources(label: 'nwcard:1 && !nwcard:2')*.name}"
                echo "or=${findLockableResources(label: 'nwcard:2 || misc')*.name}"
                """);
        j.assertBuildStatusSuccess(b1);
        j.assertLogContains("and=[resource1]", b1);
        j.assertLogContains("or=[resource2, other]", b1);
    }

    @Test
    @Issue("669")
    void returnsEmptyListWhenNothingMatches(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = run(j, "echo \"found=${findLockableResources(label: 'nothing')}\"");
        j.assertBuildStatusSuccess(b1);
        j.assertLogContains("found=[]", b1);
    }

    @Test
    @Issue("669")
    void failsWithoutLabel(JenkinsRule j) throws Exception {
        WorkflowRun b1 = run(j, "findLockableResources(label: ' ')");
        j.assertBuildStatus(Result.FAILURE, b1);
        j.assertLogContains("findLockableResources requires a label.", b1);
    }

    @Test
    @Issue("669")
    void readmeSnippetRunsInSandboxWithoutApproval(JenkinsRule j) throws Exception {
        createResources();
        WorkflowRun b1 = run(j, """
                def order = findLockableResources(label: 'nwcard:1').findAll { r -> r.labels.contains('nwcard:2') }*.name
                lock(label: 'nwcard:1', quantity: 1, order: order, variable: 'LOCKED') {
                    echo "Locked: ${env.LOCKED}"
                }
                """);
        j.assertBuildStatusSuccess(b1);
        j.assertLogContains("Locked: resource2", b1);
        assertTrue(
                ScriptApproval.get().getPendingSignatures().isEmpty(),
                Arrays.toString(ScriptApproval.get().getPendingSignatures().toArray()));
    }
}
