package org.jenkins.plugins.lockableresources;

import java.util.Arrays;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class TryLockStepTest extends LockStepTestBase {

  private static final String SCRIPT_TRY_DONT_RELEASE =
      "if (tryLock(resource: 'resource1')) {\n"
          + "  echo 'Returned True'\n"
          + "} else {\n"
          + "  echo 'Returned False'\n"
          + "}";

  private static final String SCRIPT_TRY_AND_RELEASE =
      "if (tryLock(resource: 'resource1')) {\n"
          + "  echo 'Returned True'\n"
          + "  releaseLock(resource: 'resource1')\n"
          + "} else {"
          + "  echo 'Returned False'\n"
          + "}";
  @Rule public JenkinsRule j = new JenkinsRule();

  @Test
  public void reserveAvailableResource() throws Exception {
    final LockableResourcesManager lm = LockableResourcesManager.get();
    lm.createResourceWithLabel("resource1", "label1");

    final WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(new CpsFlowDefinition(SCRIPT_TRY_DONT_RELEASE));

    final WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

    j.assertBuildStatusSuccess(j.waitForCompletion(b1));

    j.assertLogContains("Returned True", b1);
    // This comes from LockRunListener
    j.assertLogContains("[lockable-resources] released lock on [resource1]", b1);
  }

  @Test
  public void reserveUnavailableResource() throws Exception {
    final LockableResourcesManager lm = LockableResourcesManager.get();
    lm.createResourceWithLabel("resource1", "label1");
    lm.reserve(Arrays.asList(lm.fromName("resource1")), "test");

    final WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(new CpsFlowDefinition(SCRIPT_TRY_DONT_RELEASE));

    final WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

    j.assertBuildStatusSuccess(j.waitForCompletion(b1));

    j.assertLogContains("Returned False", b1);
  }

  @Test
  public void reserveAndReleaseAvailableResource() throws Exception {
    final LockableResourcesManager lm = LockableResourcesManager.get();
    lm.createResourceWithLabel("resource1", "label1");

    final WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(new CpsFlowDefinition(SCRIPT_TRY_AND_RELEASE));

    final WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

    j.assertBuildStatusSuccess(j.waitForCompletion(b1));

    j.assertLogContains("Returned True", b1);
    j.assertLogContains("Lock released on [[resource1]]", b1);
  }
}
