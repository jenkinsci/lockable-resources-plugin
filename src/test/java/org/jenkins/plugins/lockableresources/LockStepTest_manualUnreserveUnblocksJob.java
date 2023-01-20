package org.jenkins.plugins.lockableresources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

public class LockStepTest_manualUnreserveUnblocksJob extends LockStepTestBase {

  @Rule public JenkinsRule j = new JenkinsRule();

  @Issue("JENKINS-34433")
  @Test
  public void manualUnreserveUnblocksJob() throws Exception {
    LockableResourcesManager.get().createResource("resource1");
    JenkinsRule.WebClient wc = j.createWebClient();

    TestHelpers.clickButton(wc, "reserve");
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
    p.setDefinition(
      new CpsFlowDefinition(
        "lock('resource1') {\n" +
        "    echo('I am inside')\n" +
        "}\n",
        true));

    WorkflowRun r = p.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[resource1] is locked, waiting...", r);
    j.assertLogNotContains("I am inside", r);
    TestHelpers.clickButton(wc, "unreserve");
    j.waitForMessage("I am inside", r);
    j.assertLogContains("I am inside", r);
    j.assertBuildStatusSuccess(j.waitForCompletion(r));
  }
}
