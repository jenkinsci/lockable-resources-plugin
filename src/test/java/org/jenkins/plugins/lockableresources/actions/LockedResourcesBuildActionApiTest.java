package org.jenkins.plugins.lockableresources.actions;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LockedResourcesBuildActionApiTest {

    @Test
    void lockedResourcesPageExposesJsonApi(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("r1");
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "p");
        job.setDefinition(new CpsFlowDefinition("lock('r1') {}", true));
        WorkflowRun run = j.buildAndAssertSuccess(job);

        JSONObject payload =
                j.getJSON(run.getUrl() + "locked-resources/api/json").getJSONObject();
        JSONArray logs = payload.getJSONArray("logs");
        assertThat(logs.size(), greaterThan(0));

        boolean acquired = false;
        for (int i = 0; i < logs.size(); i++) {
            JSONObject entry = logs.getJSONObject(i);
            assertThat(entry.getString("name"), is("r1"));
            assertTrue(entry.getLong("timeStamp") > 0, entry.toString());
            if ("acquired".equals(entry.getString("step"))) {
                acquired = true;
                assertThat(entry.getString("action"), containsString("r1"));
            }
        }
        assertTrue(acquired, logs.toString());
        assertTrue(payload.has("resourcesInUse"));
    }
}
