package org.jenkins.plugins.lockableresources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LockStepTest_manualUnreserveUnblocksJob extends LockStepTestBase {

    @Issue("JENKINS-34433")
    @Test
    void manualUnreserveUnblocksJob(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        TestHelpers testHelpers = new TestHelpers();
        testHelpers.clickButton("reserve", "resource1");
        LockableResource resource1 = LockableResourcesManager.get().fromName("resource1");
        assertNotNull(resource1);
        resource1.setReservedBy("someone");
        assertEquals("someone", resource1.getReservedBy());
        assertTrue(resource1.isReserved());
        assertNull(resource1.getReservedTimestamp());

        JSONObject apiRes = TestHelpers.getResourceFromApi(j, "resource1", false);
        assertThat(apiRes, hasEntry("reserved", true));
        assertThat(apiRes, hasEntry("reservedBy", "someone"));

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
            lock('resource1') {
                echo('I am inside')
            }
            """,
                true));

        WorkflowRun r = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is not free, waiting for execution ...", r);
        j.assertLogNotContains("I am inside", r);
        testHelpers.clickButton("unreserve", "resource1");
        j.waitForMessage("I am inside", r);
        j.assertLogContains("I am inside", r);
        j.assertBuildStatusSuccess(j.waitForCompletion(r));
    }
}
