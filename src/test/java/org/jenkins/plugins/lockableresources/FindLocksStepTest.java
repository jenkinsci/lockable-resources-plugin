package org.jenkins.plugins.lockableresources;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class FindLocksStepTest extends LockStepTestBase {

  @Rule public JenkinsRule j = new JenkinsRule();

  @Test
  //@Issue("JENKINS-XXXXX")
  public void findAllLocks() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("Charmander", "red fire");
    LockableResourcesManager.get().createResourceWithLabel("Braviary", "red flying");
    LockableResourcesManager.get().createResourceWithLabel("Meowstic", "blue psychic");
    LockableResourcesManager.get().createResourceWithLabel("Minior", "blue flying");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
      new CpsFlowDefinition(
        "echo \"GOT ${findLocks().name}\"\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // Variable should have been filled
    j.assertLogContains("GOT [Charmander, Braviary, Meowstic, Minior]", b1);
  }

  @Test
  //@Issue("JENKINS-XXXXX")
  public void findAllLocksWithAnyLabels() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("Charmander", "red fire");
    LockableResourcesManager.get().createResourceWithLabel("Braviary", "red flying");
    LockableResourcesManager.get().createResourceWithLabel("Meowstic", "blue psychic");
    LockableResourcesManager.get().createResourceWithLabel("Minior", "blue flying");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
      new CpsFlowDefinition(
        "echo \"GOT ${findLocks(anyOfLabels:'red flying').name}\"\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // Variable should have been filled
    j.assertLogContains("GOT [Charmander, Braviary, Minior]", b1);
  }

  @Test
  //@Issue("JENKINS-XXXXX")
  public void findAllLocksWithAllOfLabels() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("Charmander", "red fire");
    LockableResourcesManager.get().createResourceWithLabel("Braviary", "red flying");
    LockableResourcesManager.get().createResourceWithLabel("Meowstic", "blue psychic");
    LockableResourcesManager.get().createResourceWithLabel("Minior", "blue flying");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
      new CpsFlowDefinition(
        "echo \"GOT ${findLocks(allOfLabels:'red flying').name}\"\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // Variable should have been filled
    j.assertLogContains("GOT [Braviary]", b1);
  }

  @Test
  //@Issue("JENKINS-XXXXX")
  public void findAllLocksWithNoneOfLabels() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("Charmander", "red fire");
    LockableResourcesManager.get().createResourceWithLabel("Braviary", "red flying");
    LockableResourcesManager.get().createResourceWithLabel("Meowstic", "blue psychic");
    LockableResourcesManager.get().createResourceWithLabel("Minior", "blue flying");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
      new CpsFlowDefinition(
        "echo \"GOT ${findLocks(noneOfLabels:'red flying').name}\"\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // Variable should have been filled
    j.assertLogContains("GOT [Meowstic]", b1);
  }

  @Test
  //@Issue("JENKINS-XXXXX")
  public void findAllLocksWithNameMatching() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("Charmander", "red fire");
    LockableResourcesManager.get().createResourceWithLabel("Braviary", "red flying");
    LockableResourcesManager.get().createResourceWithLabel("Meowstic", "blue psychic");
    LockableResourcesManager.get().createResourceWithLabel("Minior", "blue flying");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
      new CpsFlowDefinition(
        "echo \"GOT ${findLocks(matching:'^M.*').name}\"\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // Variable should have been filled
    j.assertLogContains("GOT [Meowstic, Minior]", b1);
  }

}
