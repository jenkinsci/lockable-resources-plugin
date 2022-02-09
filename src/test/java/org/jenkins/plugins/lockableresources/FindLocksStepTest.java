package org.jenkins.plugins.lockableresources;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
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
        "echo \"GOT ${findLocks(build:'any').name}\"\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // locks should have been found
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
        "echo \"GOT ${findLocks(build:'any', anyOfLabels:'red flying').name}\"\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // locks should have been found
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
        "echo \"GOT ${findLocks(build:'any', allOfLabels:'red flying').name}\"\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // locks should have been found
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
        "echo \"GOT ${findLocks(build:'any', noneOfLabels:'red flying').name}\"\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // locks should have been found
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
        "echo \"GOT ${findLocks(build:'any', matching:'^M.*').name}\"\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // locks should have been found
    j.assertLogContains("GOT [Meowstic, Minior]", b1);
  }

  @Test
  //@Issue("JENKINS-XXXXX")
  public void findAllLocksByCurrentBuild() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("Charmander", "red fire");
    LockableResourcesManager.get().createResourceWithLabel("Braviary", "red flying");
    LockableResourcesManager.get().createResourceWithLabel("Meowstic", "blue psychic");
    LockableResourcesManager.get().createResourceWithLabel("Minior", "blue flying");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
      new CpsFlowDefinition(
        "lock(label:'red') {\n"
        + "  echo \"GOT ${findLocks().name}\"\n"
        + "}\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);

    // locks should have been found
    j.assertLogContains("GOT [Charmander, Braviary]", b1);
  }

  @Test
  //@Issue("JENKINS-XXXXX")
  public void findAllLocksByAnotherBuild() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("Charmander", "red fire");
    LockableResourcesManager.get().createResourceWithLabel("Braviary", "red flying");
    LockableResourcesManager.get().createResourceWithLabel("Meowstic", "blue psychic");
    LockableResourcesManager.get().createResourceWithLabel("Minior", "blue flying");

    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
      new CpsFlowDefinition(
        "lock(label:'blue') {\n"
          + "  semaphore 'wait-inside'\n"
          + "}\n",
        true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
    p2.setDefinition(
      new CpsFlowDefinition(
        "echo \"GOT ${findLocks(build:'" + b1.getExternalizableId() + "').name}\"\n",
        true));
    WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b2);
    SemaphoreStep.success("wait-inside/1", null);
    j.waitForCompletion(b1);

    // locks should have been found
    j.assertLogContains("GOT [Meowstic, Minior]", b2);
  }

}
