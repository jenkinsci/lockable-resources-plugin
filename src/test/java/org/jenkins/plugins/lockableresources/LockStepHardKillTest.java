package org.jenkins.plugins.lockableresources;

import static org.junit.Assert.assertNotNull;

import hudson.model.Executor;
import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

public class LockStepHardKillTest extends LockStepTestBase {

  @Rule public JenkinsRule j = new JenkinsRule();

  @Issue("JENKINS-36479")
  @Test
  public void hardKillNewBuildClearsLock() throws Exception {
    LockableResourcesManager.get().createResource("resource1");

    WorkflowJob p1 = j.jenkins.createProject(WorkflowJob.class, "p");
    p1.setDefinition(
        new CpsFlowDefinition(
            "lock('resource1') { echo 'locked!'; semaphore 'wait-inside' }", true));
    WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
    j.waitForMessage("locked!", b1);
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
    p2.setDefinition(
        new CpsFlowDefinition("lock('resource1') {\n" + "  semaphore 'wait-inside'\n" + "}", true));
    WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();

    // Make sure that b2 is blocked on b1's lock.
    j.waitForMessage("[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b2);
    isPaused(b2, 1, 1);

    // Now b2 is still sitting waiting for a lock. Create b3 and launch it to clear the
    // lock.
    WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
    p3.setDefinition(
        new CpsFlowDefinition("lock('resource1') {\n" + "  semaphore 'wait-inside'\n" + "}", true));
    WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b3);
    isPaused(b3, 1, 1);

    // Kill b1 hard.
    b1.doKill();
    j.waitForMessage("Hard kill!", b1);
    j.waitForCompletion(b1);
    j.assertBuildStatus(Result.ABORTED, b1);

    // Verify that b2 gets the lock.
    j.waitForMessage("Lock acquired on [resource1]", b2);
    SemaphoreStep.success("wait-inside/2", b2);
    // Verify that b2 releases the lock and finishes successfully.
    j.waitForMessage("Lock released on resource [resource1]", b2);
    j.assertBuildStatusSuccess(j.waitForCompletion(b2));
    isPaused(b2, 1, 0);

    // Now b3 should get the lock and do its thing.
    j.waitForMessage("Lock acquired on [resource1]", b3);
    SemaphoreStep.success("wait-inside/3", b3);
    j.assertBuildStatusSuccess(j.waitForCompletion(b3));
    isPaused(b3, 1, 0);
  }

  @Issue("JENKINS-40368")
  @Test
  public void hardKillWithWaitingRuns() throws Exception {
    LockableResourcesManager.get().createResource("resource1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "retry(99) {\n"
                + "    lock('resource1') {\n"
                + "        semaphore('wait-inside')\n"
                + "     }\n"
                + "}",
            true));

    WorkflowRun prevBuild = null;
    for (int i = 0; i < 3; i++) {
      WorkflowRun rNext = p.scheduleBuild2(0).waitForStart();
      if (prevBuild != null) {
        j.waitForMessage(
            "[resource1] is locked by " + prevBuild.getFullDisplayName() + ", waiting...", rNext);
        isPaused(rNext, 1, 1);
        interruptTermKill(prevBuild);
      }

      j.waitForMessage("Lock acquired on [resource1]", rNext);

      SemaphoreStep.waitForStart("wait-inside/" + (i + 1), rNext);
      isPaused(rNext, 1, 0);
      prevBuild = rNext;
    }
    SemaphoreStep.success("wait-inside/3", null);
    j.assertBuildStatus(Result.SUCCESS, j.waitForCompletion(prevBuild));
  }

  @Issue("JENKINS-40368")
  @Test
  public void hardKillWithWaitingRunsOnLabel() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
    LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "retry(99) {\n"
                + "    lock(label: 'label1', quantity: 1) {\n"
                + "        semaphore('wait-inside')\n"
                + "     }\n"
                + "}",
            true));

    WorkflowRun firstPrev = null;
    WorkflowRun secondPrev = null;
    for (int i = 0; i < 3; i++) {
      WorkflowRun firstNext = p.scheduleBuild2(0).waitForStart();
      j.waitForMessage("Trying to acquire lock on", firstNext);
      WorkflowRun secondNext = p.scheduleBuild2(0).waitForStart();
      j.waitForMessage("Trying to acquire lock on", secondNext);

      if (firstPrev != null) {
        j.waitForMessage("is locked, waiting...", firstNext);
        isPaused(firstNext, 1, 1);
        j.waitForMessage("is locked, waiting...", secondNext);
        isPaused(secondNext, 1, 1);
      }

      interruptTermKill(firstPrev);
      j.waitForMessage("Lock acquired on ", firstNext);
      interruptTermKill(secondPrev);
      j.waitForMessage("Lock acquired on ", secondNext);

      SemaphoreStep.waitForStart("wait-inside/" + ((i * 2) + 1), firstNext);
      SemaphoreStep.waitForStart("wait-inside/" + ((i * 2) + 2), secondNext);
      isPaused(firstNext, 1, 0);
      isPaused(secondNext, 1, 0);
      firstPrev = firstNext;
      secondPrev = secondNext;
    }
    SemaphoreStep.success("wait-inside/5", null);
    SemaphoreStep.success("wait-inside/6", null);
    j.assertBuildStatus(Result.SUCCESS, j.waitForCompletion(firstPrev));
    j.assertBuildStatus(Result.SUCCESS, j.waitForCompletion(secondPrev));
  }

  private void interruptTermKill(WorkflowRun b) throws Exception {
    if (b != null) {
      Executor ex = b.getExecutor();
      assertNotNull(ex);
      ex.interrupt();
      j.waitForCompletion(b);
      j.assertBuildStatus(Result.ABORTED, b);
    }
  }
}
