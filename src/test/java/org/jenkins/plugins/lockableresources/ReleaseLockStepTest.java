package org.jenkins.plugins.lockableresources;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class ReleaseLockStepTest extends LockStepTestBase {

  private static final String SCRIPT_LOCK_AND_RELEASE_WITHOUT_RESOURCE =
      "if (tryLock(resource: 'resource1')) {\n"
          + "  echo 'acquired first lock'\n"
          + "  releaseLock()\n"
          + "}\n\n"
          + "if (tryLock(resource: 'resource1')) {\n"
          + "  echo 'acquired second lock'\n"
          + "  releaseLock()\n"
          + "}";

  private static final String SCRIPT_LOCK_AND_RELEASE_WITH_RESOURCE =
      "if (tryLock(resource: 'resource1')) {\n"
          + "  echo 'acquired first lock'\n"
          + "  releaseLock(resource: 'resource1')\n"
          + "}\n\n"
          + "if (tryLock(resource: 'resource1')) {\n"
          + "  echo 'acquired second lock'\n"
          + "  releaseLock(resource: 'resource1')\n"
          + "}";

  private static final String SCRIPT_LOCK_AND_RELEASE_NOT_LOCKED_RESOURCE =
      "if (tryLock(resource: 'resource1')) {\n"
          + "  echo 'acquired first lock'\n"
          + "  releaseLock(resource: 'resource2')\n"
          + "}\n";

  private static final String SCRIPT_RELEASE_NOT_LOCKED_RESOURCE =
      "releaseLock(resource: 'resource1')";

  @Rule public JenkinsRule j = new JenkinsRule();

  @Test
  public void releaseLockWithoutSpecifyingTheName() throws Exception {
    final LockableResourcesManager lm = LockableResourcesManager.get();
    lm.createResourceWithLabel("resource1", "label1");

    final WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(new CpsFlowDefinition(SCRIPT_LOCK_AND_RELEASE_WITHOUT_RESOURCE));

    final WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

    j.assertBuildStatusSuccess(j.waitForCompletion(b1));

    j.assertLogContains("acquired first lock", b1);
    j.assertLogContains("acquired second lock", b1);
  }

  @Test
  public void releaseLockBySpecifyingTheName() throws Exception {
    final LockableResourcesManager lm = LockableResourcesManager.get();
    lm.createResourceWithLabel("resource1", "label1");

    final WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(new CpsFlowDefinition(SCRIPT_LOCK_AND_RELEASE_WITH_RESOURCE));

    final WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

    j.assertBuildStatusSuccess(j.waitForCompletion(b1));

    j.assertLogContains("acquired first lock", b1);
    j.assertLogContains("acquired second lock", b1);
  }

  @Test
  public void lockAndReleaseNotLockedResource() throws Exception {
    final LockableResourcesManager lm = LockableResourcesManager.get();
    lm.createResourceWithLabel("resource1", "label1");

    final WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(new CpsFlowDefinition(SCRIPT_LOCK_AND_RELEASE_NOT_LOCKED_RESOURCE));

    final WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

    j.assertBuildStatusSuccess(j.waitForCompletion(b1));

    j.assertLogContains(
        "Cannot release lock resource2 as it was not acquired by the build: [resource1]", b1);
  }

  @Test
  public void releaseNotLockedResource() throws Exception {
    final LockableResourcesManager lm = LockableResourcesManager.get();
    lm.createResourceWithLabel("resource1", "label1");

    final WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(new CpsFlowDefinition(SCRIPT_RELEASE_NOT_LOCKED_RESOURCE));

    final WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

    j.assertBuildStatusSuccess(j.waitForCompletion(b1));

    j.assertLogContains("Cannot release any locks as none are acquired", b1);
  }
}
