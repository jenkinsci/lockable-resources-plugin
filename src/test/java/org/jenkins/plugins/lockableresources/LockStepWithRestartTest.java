package org.jenkins.plugins.lockableresources;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.RestartableJenkinsRule;

public class LockStepWithRestartTest extends LockStepTestBase {

  @Rule public RestartableJenkinsRule story = new RestartableJenkinsRule();

  @Test
  public void lockOrderRestart() {
    story.addStep(
        new Statement() {
          @Override
          public void evaluate() throws Throwable {
            LockableResourcesManager.get().createResource("resource1");
            WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(
                new CpsFlowDefinition(
                    "lock('resource1') {\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'"));
            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait-inside/1", b1);
            WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
            // Ensure that b2 reaches the lock before b3
            story.j.waitForMessage("[resource1] is locked, waiting...", b2);
            isPaused(b2, 1, 1);
            WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
            // Both 2 and 3 are waiting for locking resource1

            story.j.waitForMessage("[resource1] is locked, waiting...", b3);
            isPaused(b3, 1, 1);
          }
        });

    story.addStep(
        new Statement() {
          @Override
          public void evaluate() throws Throwable {
            WorkflowJob p = story.j.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b1 = p.getBuildByNumber(1);
            WorkflowRun b2 = p.getBuildByNumber(2);
            WorkflowRun b3 = p.getBuildByNumber(3);

            // Unlock resource1
            SemaphoreStep.success("wait-inside/1", null);
            story.j.waitForMessage("Lock released on resource [resource1]", b1);
            isPaused(b1, 1, 0);

            story.j.waitForMessage("Lock acquired on [resource1]", b2);
            isPaused(b2, 1, 0);
            story.j.assertLogContains("[resource1] is locked, waiting...", b3);
            isPaused(b3, 1, 1);
            SemaphoreStep.success("wait-inside/2", null);
            SemaphoreStep.waitForStart("wait-inside/3", b3);
            story.j.assertLogContains("Lock acquired on [resource1]", b3);
            SemaphoreStep.success("wait-inside/3", null);
            story.j.waitForMessage("Finish", b3);
            isPaused(b3, 1, 0);
          }
        });
  }

  @Test
  public void interoperabilityOnRestart() {
    story.addStep(
        new Statement() {
          @Override
          public void evaluate() throws Throwable {
            LockableResourcesManager.get().createResource("resource1");
            WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(
                new CpsFlowDefinition(
                    "lock('resource1') {\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'"));
            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait-inside/1", b1);
            isPaused(b1, 1, 0);

            FreeStyleProject f = story.j.createFreeStyleProject("f");
            f.addProperty(new RequiredResourcesProperty("resource1", null, null, null, null));

            f.scheduleBuild2(0);

            while (story.j.jenkins.getQueue().getItems().length != 1) {
              System.out.println("Waiting for freestyle to be queued...");
              Thread.sleep(1000);
            }
          }
        });

    story.addStep(
        new Statement() {
          @Override
          public void evaluate() throws Throwable {
            WorkflowJob p = story.j.jenkins.getItemByFullName("p", WorkflowJob.class);
            FreeStyleProject f = story.j.jenkins.getItemByFullName("f", FreeStyleProject.class);
            WorkflowRun b1 = p.getBuildByNumber(1);

            // Unlock resource1
            SemaphoreStep.success("wait-inside/1", null);
            story.j.waitForMessage("Lock released on resource [resource1]", b1);
            isPaused(b1, 1, 0);
            FreeStyleBuild fb1 = null;
            while ((fb1 = f.getBuildByNumber(1)) == null) {
              System.out.println("Waiting for freestyle #1 to start building...");
              Thread.sleep(1000);
            }

            story.j.waitForMessage("acquired lock on [resource1]", fb1);
          }
        });
  }
}
