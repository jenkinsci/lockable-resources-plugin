package org.jenkins.plugins.lockableresources;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import java.util.Collections;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

public class LockStepWithRestartTest extends LockStepTestBase {

  @Rule public RestartableJenkinsRule story = new RestartableJenkinsRule();

  @Test
  public void lockOrderRestart() {
    story.then(
        (JenkinsRule j) -> {
          LockableResourcesManager.get().createResource("resource1");
          WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
          p.setDefinition(
              new CpsFlowDefinition(
                  "lock('resource1') {\n" + "  semaphore 'wait-inside'\n" + "}\n" + "echo 'Finish'",
                  true));
          WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
          SemaphoreStep.waitForStart("wait-inside/1", b1);
          WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
          // Ensure that b2 reaches the lock before b3
          j.waitForMessage(
              "[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b2);
          isPaused(b2, 1, 1);
          WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
          // Both 2 and 3 are waiting for locking resource1

          j.waitForMessage(
              "[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b3);
          isPaused(b3, 1, 1);
        });

    story.then(
        (JenkinsRule j) -> {
          WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
          WorkflowRun b1 = p.getBuildByNumber(1);
          WorkflowRun b2 = p.getBuildByNumber(2);
          WorkflowRun b3 = p.getBuildByNumber(3);

          // Unlock resource1
          SemaphoreStep.success("wait-inside/1", null);
          j.waitForMessage("Lock released on resource [resource1]", b1);
          isPaused(b1, 1, 0);

          j.waitForMessage("Lock acquired on [resource1]", b2);
          isPaused(b2, 1, 0);
          j.assertLogContains(
              "[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b3);
          isPaused(b3, 1, 1);
          SemaphoreStep.success("wait-inside/2", null);
          SemaphoreStep.waitForStart("wait-inside/3", b3);
          j.assertLogContains("Lock acquired on [resource1]", b3);
          SemaphoreStep.success("wait-inside/3", null);
          j.waitForMessage("Finish", b3);
          isPaused(b3, 1, 0);
        });
  }

  @Test
  public void interoperabilityOnRestart() {
    story.then(
        (JenkinsRule j) -> {
          LockableResourcesManager.get().createResource("resource1");
          WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
          p.setDefinition(
              new CpsFlowDefinition(
                  "lock('resource1') {\n" + "  semaphore 'wait-inside'\n" + "}\n" + "echo 'Finish'",
                  true));
          WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
          SemaphoreStep.waitForStart("wait-inside/1", b1);
          isPaused(b1, 1, 0);

          FreeStyleProject f = j.createFreeStyleProject("f");
          f.addProperty(new RequiredResourcesProperty("resource1", null, null, null, null));

          f.scheduleBuild2(0);
          TestHelpers.waitForQueue(j.jenkins, f);
        });

    story.then(
        (JenkinsRule j) -> {
          WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
          FreeStyleProject f = j.jenkins.getItemByFullName("f", FreeStyleProject.class);
          WorkflowRun b1 = p.getBuildByNumber(1);

          // Unlock resource1
          SemaphoreStep.success("wait-inside/1", null);
          j.waitForMessage("Lock released on resource [resource1]", b1);
          isPaused(b1, 1, 0);

          FreeStyleBuild fb1 = null;
          System.out.print("Waiting for freestyle #1 to start building");
          while ((fb1 = f.getBuildByNumber(1)) == null) {
            Thread.sleep(250);
            System.out.print('.');
          }
          System.out.println();

          j.waitForMessage("acquired lock on [resource1]", fb1);
        });
  }

  @Test
  public void testReserveOverRestart() {
    story.then(
        (JenkinsRule j) -> {
          LockableResourcesManager manager = LockableResourcesManager.get();
          manager.createResource("resource1");
          manager.reserve(Collections.singletonList(manager.fromName("resource1")), "user");

          WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
          p.setDefinition(
              new CpsFlowDefinition(
                  "lock('resource1') {\n" + "  echo 'inside'\n" + "}\n" + "echo 'Finish'", true));
          WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
          j.waitForMessage("[resource1] is locked, waiting...", b1);
          isPaused(b1, 1, 1);

          FreeStyleProject f = j.createFreeStyleProject("f");
          f.addProperty(new RequiredResourcesProperty("resource1", null, null, null, null));

          f.scheduleBuild2(0);
          TestHelpers.waitForQueue(j.jenkins, f);
        });

    story.then(
        (JenkinsRule j) -> {
          WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
          FreeStyleProject f = j.jenkins.getItemByFullName("f", FreeStyleProject.class);
          WorkflowRun b1 = p.getBuildByNumber(1);

          LockableResourcesManager manager = LockableResourcesManager.get();
          manager.createResource("resource1");
          manager.unreserve(Collections.singletonList(manager.fromName("resource1")));

          j.waitForMessage("Lock released on resource [resource1]", b1);
          isPaused(b1, 1, 0);
          j.waitForMessage("Finish", b1);
          isPaused(b1, 1, 0);

          j.waitUntilNoActivity();
        });
  }
}
