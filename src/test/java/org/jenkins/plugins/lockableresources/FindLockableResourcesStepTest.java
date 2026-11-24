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
    void returnsNameLabelsGroupDescriptionAndNote(JenkinsRule j) throws Exception {
        createResources();

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("name", "resource2");
        expected.put("labels", List.of("nwcard:1", "nwcard:2"));
        expected.put("group", ResourceGroup.DEFAULT_NAME);
        expected.put("description", "second card");
        expected.put("note", "spare");
        expected.put("properties", Map.of());
        assertEquals(List.of(expected), FindLockableResourcesStep.find("nwcard:2"));

        WorkflowRun b1 = run(j, """
                def found = findLockableResources(label: 'nwcard:2')
                echo "size=${found.size()}"
                echo "keys=${found[0].keySet()}"
                echo "found=${found[0].name}|${found[0].labels}|${found[0].group}|${found[0].description}|${found[0].note}"
                """);
        j.assertBuildStatusSuccess(b1);
        j.assertLogContains("size=1", b1);
        j.assertLogContains("keys=[name, labels, group, description, note, properties]", b1);
        j.assertLogContains("found=resource2|[nwcard:1, nwcard:2]|common|second card|spare", b1);
    }

    @Test
    @Issue("669")
    void returnsProperties(JenkinsRule j) throws Exception {
        createResources();
        LockableResourceProperty ip = new LockableResourceProperty();
        ip.setName("IP");
        ip.setValue("10.0.0.2");
        LockableResourceProperty port = new LockableResourceProperty();
        port.setName("PORT");
        port.setValue("4711");
        LockableResourcesManager.get().fromName("resource2").setProperties(List.of(ip, port));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("IP", "10.0.0.2");
        expected.put("PORT", "4711");
        assertEquals(expected, FindLockableResourcesStep.find("nwcard:2").get(0).get("properties"));
        assertEquals(Map.of(), FindLockableResourcesStep.find("misc").get(0).get("properties"));

        WorkflowRun b1 = run(j, """
                def found = findLockableResources(label: 'nwcard:1')
                echo "props=${found.collect { r -> r.name + '=' + r.properties }}"
                echo "ip=${found.find { r -> r.name == 'resource2' }.properties.IP}"
                """);
        j.assertBuildStatusSuccess(b1);
        j.assertLogContains("props=[resource1=[:], resource2=[IP:10.0.0.2, PORT:4711]]", b1);
        j.assertLogContains("ip=10.0.0.2", b1);
    }

    @Test
    @Issue("669")
    void groupFiltersAfterLabelMatch(JenkinsRule j) throws Exception {
        createResources();
        LockableResourcesManager.get().fromName("resource2").setGroup("plc");

        assertEquals(1, FindLockableResourcesStep.find("nwcard:1", " plc ").size());
        WorkflowRun b1 = run(j, """
                echo "plc=${findLockableResources(label: 'nwcard:1', group: 'plc').collect { r -> r.name + ':' + r.group }}"
                echo "common=${findLockableResources(label: 'nwcard:1', group: 'common')*.name}"
                echo "all=${findLockableResources(label: 'nwcard:1')*.name}"
                echo "blank=${findLockableResources(label: 'nwcard:1', group: ' ')*.name}"
                echo "unknown=${findLockableResources(label: 'nwcard:1', group: 'missing')}"
                """);
        j.assertBuildStatusSuccess(b1);
        j.assertLogContains("plc=[resource2:plc]", b1);
        j.assertLogContains("common=[resource1]", b1);
        j.assertLogContains("all=[resource1, resource2]", b1);
        j.assertLogContains("blank=[resource1, resource2]", b1);
        j.assertLogContains("unknown=[]", b1);
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
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("deb1", "os:Debian nwcard:1");
        lrm.createResourceWithLabel("deb2", "os:Debian nwcard:1 nwcard:2");
        lrm.createResourceWithLabel("deb3", "os:Debian nwcard:1 nwcard:2");
        lrm.createResourceWithLabel("deb4", "os:Debian nwcard:1");
        lrm.createResourceWithLabel("win1", "os:Windows nwcard:1 nwcard:2");
        lrm.fromName("deb2").setDescription("Test-agent in lab 2");

        // the README example, unchanged
        WorkflowRun b1 = run(j, """
                def preferred = findLockableResources(label: 'os:Debian && nwcard:1').findAll { r -> !r.description.contains('Test-agent') }
                def sorted = preferred.findAll { r -> r.labels.contains('nwcard:2') } + preferred.findAll { r -> !r.labels.contains('nwcard:2') }
                lock(candidates: sorted*.name, quantity: 2, variable: 'LOCKED') { echo 'Locked: ' + env.LOCKED }
                """);
        j.assertBuildStatusSuccess(b1);
        // deb2 is a test agent and win1 is not Debian; deb3 has nwcard:2 so it comes first
        j.assertLogContains("Locked: deb3,deb1", b1);
        assertTrue(
                ScriptApproval.get().getPendingSignatures().isEmpty(),
                Arrays.toString(ScriptApproval.get().getPendingSignatures().toArray()));
    }
}
