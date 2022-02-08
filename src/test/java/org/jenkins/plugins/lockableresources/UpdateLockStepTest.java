package org.jenkins.plugins.lockableresources;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class UpdateLockStepTest extends LockStepTestBase {

  @Rule
  public JenkinsRule j = new JenkinsRule();

  @Test
  //@Issue("JENKINS-XXXXX")
  public void updateLockAddLabels() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
      new CpsFlowDefinition(
        "updateLock(resource:'resource1', addLabels:'newLabel1 newLabel2')\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // labels should have been added
    Assert.assertEquals(
      "label1 newLabel1 newLabel2",
      LockableResourcesManager.get().fromName("resource1").getLabels());
  }

  @Test
  //@Issue("JENKINS-XXXXX")
  public void updateLockRemoveLabels() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("resource1", "label1 label2");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
      new CpsFlowDefinition(
        "updateLock(resource:'resource1', removeLabels:'label1')\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // label should have been removed
    Assert.assertEquals(
      "label2",
      LockableResourcesManager.get().fromName("resource1").getLabels());
  }

  @Test
  //@Issue("JENKINS-XXXXX")
  public void updateLockSetLabels() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("resource1", "label1 label2");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
      new CpsFlowDefinition(
        "updateLock(resource:'resource1', setLabels:'a b c')\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // labels should have been set
    Assert.assertEquals(
      "a b c",
      LockableResourcesManager.get().fromName("resource1").getLabels());
  }

  @Test
  //@Issue("JENKINS-XXXXX")
  public void updateLockSetNote() throws Exception {
    LockableResourcesManager.get().createResource("resource1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
      new CpsFlowDefinition(
        "updateLock(resource:'resource1', setNote:'hello world')\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // Note should have been updated
    Assert.assertEquals(
      "hello world",
      LockableResourcesManager.get().fromName("resource1").getNote());
  }

  @Test
  //@Issue("JENKINS-XXXXX")
  public void updateLockCreateResource() throws Exception {
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
      new CpsFlowDefinition(
        "updateLock(resource:'newResource', createResource:true)\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // Resource should be created (not ephemeral)
    Assert.assertNotNull(LockableResourcesManager.get().fromName("newResource"));
    Assert.assertFalse(LockableResourcesManager.get().fromName("newResource").isEphemeral());
  }

  @Test
  //@Issue("JENKINS-XXXXX")
  public void updateLockDeleteResource() throws Exception {
    LockableResourcesManager.get().createResource("resource1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
      new CpsFlowDefinition(
        "updateLock(resource:'resource1', deleteResource:true)\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // Resource should be deleted
    Assert.assertNull(LockableResourcesManager.get().fromName("resource1"));
  }


  @Test
  //@Issue("JENKINS-XXXXX")
  public void updateLockDeleteLockedResource() throws Exception {
    LockableResourcesManager.get().createResource("resource1");
    LockableResourcesManager.get().fromName("resource1").setEphemeral(false);

    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
      new CpsFlowDefinition(
        "lock(resource:'resource1') {\n"
          + "  semaphore 'wait-inside'\n"
          + "}\n"
          + "echo 'Finish'",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
    p2.setDefinition(
      new CpsFlowDefinition(
        "updateLock(resource:'resource1', deleteResource:true)\n",
        true));
    WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b2);

    Assert.assertNotNull(LockableResourcesManager.get().fromName("resource1"));
    Assert.assertTrue(LockableResourcesManager.get().fromName("resource1").isEphemeral());

    SemaphoreStep.success("wait-inside/1", null);
    j.waitForCompletion(b1);

    Assert.assertNull(LockableResourcesManager.get().fromName("resource1"));
  }
}
