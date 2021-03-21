package org.jenkins.plugins.lockableresources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.jenkins.plugins.lockableresources.TestHelpers.clickButton;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import hudson.Functions;
import hudson.model.Result;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.WithPlugin;

public class LockStepTest extends LockStepTestBase {

  @Rule public JenkinsRule j = new JenkinsRule();

  @Test
  public void autoCreateResource() throws Exception {
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock('resource1') {\n" + "	echo 'Resource locked'\n" + "}\n" + "echo 'Finish'", true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);
    j.assertBuildStatus(Result.SUCCESS, b1);
    j.assertLogContains("Resource [resource1] did not exist. Created.", b1);

    assertNull(LockableResourcesManager.get().fromName("resource1"));
  }

  @Test
  public void lockNothing() throws Exception {
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock() {\n" + "  echo 'Nothing locked.'\n" + "}\n" + "echo 'Finish'", true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);
    j.assertBuildStatus(Result.SUCCESS, b1);
    j.assertLogContains("Lock acquired on [nothing]", b1);
  }

  @Test
  public void lockWithLabel() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1', variable: 'var') {\n"
                + "	echo \"Resource locked: ${env.var}\"\n"
                + "}\n"
                + "echo 'Finish'",
            true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);
    j.assertBuildStatus(Result.SUCCESS, b1);
    j.assertLogContains("Lock released on resource [Label: label1]", b1);
    j.assertLogContains("Resource locked: resource1", b1);
    isPaused(b1, 1, 0);

    assertNotNull(LockableResourcesManager.get().fromName("resource1"));
  }

  @Test
  public void lockOrderLabel() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
    LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
    LockableResourcesManager.get().createResourceWithLabel("resource3", "label1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1', quantity: 2) {\n"
                + "	semaphore 'wait-inside'\n"
                + "}\n"
                + "echo 'Finish'",
            true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
    // Ensure that b2 reaches the lock before b3
    j.waitForMessage("[Label: label1, Quantity: 2] is locked, waiting...", b2);
    j.waitForMessage("Found 1 available resource(s). Waiting for correct amount: 2.", b2);
    isPaused(b2, 1, 1);
    WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
    // Both 2 and 3 are waiting for locking Label: label1, Quantity: 2
    j.waitForMessage("[Label: label1, Quantity: 2] is locked, waiting...", b3);
    j.waitForMessage("Found 1 available resource(s). Waiting for correct amount: 2.", b3);
    isPaused(b3, 1, 1);

    // Unlock Label: label1, Quantity: 2
    SemaphoreStep.success("wait-inside/1", null);
    j.waitForMessage("Lock released on resource [Label: label1, Quantity: 2]", b1);
    isPaused(b1, 1, 0);

    // #2 gets the lock before #3 (in the order as they requested the lock)
    j.waitForMessage("Lock acquired on [Label: label1, Quantity: 2]", b2);
    SemaphoreStep.success("wait-inside/2", null);
    j.waitForMessage("Finish", b2);
    isPaused(b2, 1, 0);
    j.waitForMessage("Lock acquired on [Label: label1, Quantity: 2]", b3);
    SemaphoreStep.success("wait-inside/3", null);
    j.waitForMessage("Finish", b3);
    isPaused(b3, 1, 0);

    assertNotNull(LockableResourcesManager.get().fromName("resource1"));
    assertNotNull(LockableResourcesManager.get().fromName("resource2"));
    assertNotNull(LockableResourcesManager.get().fromName("resource3"));
  }

  @Test
  public void lockOrderLabelQuantity() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
    LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
    LockableResourcesManager.get().createResourceWithLabel("resource3", "label1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1', quantity: 2) {\n"
                + "	semaphore 'wait-inside'\n"
                + "}\n"
                + "echo 'Finish'",
            true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
    // Ensure that b2 reaches the lock before b3
    j.waitForMessage("[Label: label1, Quantity: 2] is locked, waiting...", b2);
    j.waitForMessage("Found 1 available resource(s). Waiting for correct amount: 2.", b2);
    isPaused(b2, 1, 1);

    WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
    p3.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1', quantity: 1) {\n"
                + "	semaphore 'wait-inside-quantity1'\n"
                + "}\n"
                + "echo 'Finish'",
            true));
    WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
    // While 2 continues waiting, 3 can continue directly
    SemaphoreStep.waitForStart("wait-inside-quantity1/1", b3);
    // Let 3 finish
    SemaphoreStep.success("wait-inside-quantity1/1", null);
    j.waitForMessage("Finish", b3);
    isPaused(b3, 1, 0);

    // Unlock Label: label1, Quantity: 2
    SemaphoreStep.success("wait-inside/1", null);
    j.waitForMessage("Lock released on resource [Label: label1, Quantity: 2]", b1);
    isPaused(b1, 1, 0);

    // #2 gets the lock before #3 (in the order as they requested the lock)
    j.waitForMessage("Lock acquired on [Label: label1, Quantity: 2]", b2);
    SemaphoreStep.success("wait-inside/2", null);
    j.waitForMessage("Finish", b2);
    isPaused(b2, 1, 0);

    assertNotNull(LockableResourcesManager.get().fromName("resource1"));
    assertNotNull(LockableResourcesManager.get().fromName("resource2"));
    assertNotNull(LockableResourcesManager.get().fromName("resource3"));
  }

  @Test
  public void lockOrderLabelQuantityFreedResources() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
    LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
    LockableResourcesManager.get().createResourceWithLabel("resource3", "label1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1') {\n" + "	semaphore 'wait-inside'\n" + "}\n" + "echo 'Finish'",
            true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    JSONObject apiRes = TestHelpers.getResourceFromApi(j, "resource1", true);
    assertThat(apiRes, hasEntry("labels", "label1"));

    WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
    p2.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1', quantity: 2) {\n"
                + "	semaphore 'wait-inside-quantity2'\n"
                + "}\n"
                + "echo 'Finish'",
            true));
    WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
    // Ensure that b2 reaches the lock before b3
    j.waitForMessage("[Label: label1, Quantity: 2] is locked, waiting...", b2);
    j.waitForMessage("Found 0 available resource(s). Waiting for correct amount: 2.", b2);
    isPaused(b2, 1, 1);

    WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
    p3.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1', quantity: 1) {\n"
                + "	semaphore 'wait-inside-quantity1'\n"
                + "}\n"
                + "echo 'Finish'",
            true));
    WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[Label: label1, Quantity: 1] is locked, waiting...", b3);
    j.waitForMessage("Found 0 available resource(s). Waiting for correct amount: 1.", b3);
    isPaused(b3, 1, 1);

    // Unlock Label: label1
    SemaphoreStep.success("wait-inside/1", null);
    j.waitForMessage("Lock released on resource [Label: label1]", b1);
    isPaused(b1, 1, 0);

    // Both get their lock
    j.waitForMessage("Lock acquired on [Label: label1, Quantity: 2]", b2);
    j.waitForMessage("Lock acquired on [Label: label1, Quantity: 1]", b3);

    SemaphoreStep.success("wait-inside-quantity2/1", null);
    SemaphoreStep.success("wait-inside-quantity1/1", null);
    j.waitForMessage("Finish", b2);
    j.waitForMessage("Finish", b3);
    isPaused(b2, 1, 0);
    isPaused(b3, 1, 0);

    assertNotNull(LockableResourcesManager.get().fromName("resource1"));
    assertNotNull(LockableResourcesManager.get().fromName("resource2"));
    assertNotNull(LockableResourcesManager.get().fromName("resource3"));
  }

  @Test
  public void lockOrder() throws Exception {
    LockableResourcesManager.get().createResource("resource1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock('resource1') {\n" + "	semaphore 'wait-inside'\n" + "}\n" + "echo 'Finish'",
            true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
    // Ensure that b2 reaches the lock before b3
    j.waitForMessage("[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b2);
    isPaused(b2, 1, 1);
    WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
    // Both 2 and 3 are waiting for locking resource1

    j.waitForMessage("[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b3);
    isPaused(b3, 1, 1);

    // Unlock resource1
    SemaphoreStep.success("wait-inside/1", null);
    j.waitForMessage("Lock released on resource [resource1]", b1);
    isPaused(b1, 1, 0);

    // #2 gets the lock before #3 (in the order as they requested the lock)
    j.waitForMessage("Lock acquired on [resource1]", b2);
    SemaphoreStep.success("wait-inside/2", null);
    isPaused(b2, 1, 0);
    j.waitForMessage("Lock acquired on [resource1]", b3);
    SemaphoreStep.success("wait-inside/3", null);
    j.waitForMessage("Finish", b3);
    isPaused(b3, 1, 0);
  }

  @Test
  public void lockInverseOrder() throws Exception {
    LockableResourcesManager.get().createResource("resource1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock(resource: 'resource1', inversePrecedence: true) {\n"
                + "	semaphore 'wait-inside'\n"
                + "}\n"
                + "echo 'Finish'",
            true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
    // Ensure that b2 reaches the lock before b3
    j.waitForMessage("[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b2);
    isPaused(b2, 1, 1);
    WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
    // Both 2 and 3 are waiting for locking resource1

    j.waitForMessage("[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b3);
    isPaused(b3, 1, 1);

    // Unlock resource1
    SemaphoreStep.success("wait-inside/1", null);
    j.waitForMessage("Lock released on resource [resource1]", b1);
    isPaused(b1, 1, 0);

    // #3 gets the lock before #2 because of inversePrecedence
    j.waitForMessage("Lock acquired on [resource1]", b3);
    SemaphoreStep.success("wait-inside/2", null);
    isPaused(b3, 1, 0);
    j.waitForMessage("Lock acquired on [resource1]", b2);
    SemaphoreStep.success("wait-inside/3", null);
    j.waitForMessage("Finish", b3);
    isPaused(b2, 1, 0);
  }

  @Test
  public void parallelLock() throws Exception {
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "parallel a: {\n"
                + "	semaphore 'before-a'\n"
                + "	lock('resource1') {\n"
                + "		semaphore 'inside-a'\n"
                + "	}\n"
                + "}, b: {\n"
                + "	lock('resource1') {\n"
                + "		semaphore 'wait-b'\n"
                + "	}\n"
                + "}\n",
            true));

    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-b/1", b1);
    SemaphoreStep.waitForStart("before-a/1", b1);
    // both messages are in the log because branch b acquired the lock and branch a is waiting to
    // lock
    j.waitForMessage("Lock acquired on [resource1]", b1);
    SemaphoreStep.success("before-a/1", null);
    j.waitForMessage("[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b1);
    isPaused(b1, 2, 1);

    SemaphoreStep.success("wait-b/1", null);

    j.waitForMessage("Lock acquired on [resource1]", b1);
    SemaphoreStep.waitForStart("inside-a/1", b1);
    isPaused(b1, 2, 0);
    SemaphoreStep.success("inside-a/1", null);

    j.waitForCompletion(b1);
    assertNull(LockableResourcesManager.get().fromName("resource1"));
  }

  /* TODO: This test does not run on Windows. Before wasting another afternoon trying to fix this, I'd suggest watching
   * a good movie instead. If you really want to try your luck, here are some pointers:
   * - Windows doesn't like to delete files that are currently in use
   * - When deleting a running pipeline job, the listener keeps its logfile open
   * This has the potential to fail at two points:
   * - Right when deleting the run: Jenkins tries to remove the run directory, which contains the open log file
   * - After the test, on cleanup, the jenkins test harness tries to remove the complete Jenkins data directory
   * Things already tried: Getting a handle on the listener and closing its logfile.
   * Stupid idea: Implement a JEP-210 extension, which keeps log files in memory...
   */
  @Issue("JENKINS-36479")
  @Test
  public void deleteRunningBuildNewBuildClearsLock() throws Exception {
    assumeFalse(Functions.isWindows());

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

    // Now b2 is still sitting waiting for a lock. Create b3 and launch it to verify order of
    // unlock.
    WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
    p3.setDefinition(
        new CpsFlowDefinition("lock('resource1') {\n" + "  semaphore 'wait-inside'\n" + "}", true));
    WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b3);
    isPaused(b3, 1, 1);

    b1.delete();

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

  @Test
  public void unlockButtonWithWaitingRuns() throws Exception {
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

    JenkinsRule.WebClient wc = j.createWebClient();

    WorkflowRun prevBuild = null;
    for (int i = 0; i < 3; i++) {
      WorkflowRun rNext = p.scheduleBuild2(0).waitForStart();
      if (prevBuild != null) {
        j.waitForMessage(
            "[resource1] is locked by " + prevBuild.getFullDisplayName() + ", waiting...", rNext);
        isPaused(rNext, 1, 1);
        clickButton(wc, "unlock");
      }

      j.waitForMessage("Lock acquired on [resource1]", rNext);
      SemaphoreStep.waitForStart("wait-inside/" + (i + 1), rNext);
      isPaused(rNext, 1, 0);

      if (prevBuild != null) {
        SemaphoreStep.success("wait-inside/" + i, null);
        j.assertBuildStatusSuccess(j.waitForCompletion(prevBuild));
      }
      prevBuild = rNext;
    }
    SemaphoreStep.success("wait-inside/3", null);
    j.assertBuildStatus(Result.SUCCESS, j.waitForCompletion(prevBuild));
  }

  @Issue("JENKINS-40879")
  @Test
  public void parallelLockRelease() throws Exception {
    LockableResourcesManager.get().createResource("resource1");
    LockableResourcesManager.get().createResource("resource2");
    WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "j");
    job.setDefinition(
        new CpsFlowDefinition(
            "lock(resource: 'resource1') {\n"
                + "    semaphore 'wait-inside-1'\n"
                + "}\n"
                + "lock(resource: 'resource2') { \n"
                + "    echo 'Entering semaphore now'\n"
                + "    semaphore 'wait-inside-2'\n"
                + "}\n",
            true));

    List<WorkflowRun> nextRuns = new ArrayList<>();

    WorkflowRun toUnlock = null;
    for (int i = 0; i < 5; i++) {
      WorkflowRun rNext = job.scheduleBuild2(0).waitForStart();
      if (toUnlock != null) {
        j.waitForMessage(
            "[resource1] is locked by " + toUnlock.getFullDisplayName() + ", waiting...", rNext);
        isPaused(rNext, 1, 1);
        SemaphoreStep.success("wait-inside-1/" + i, null);
      }
      SemaphoreStep.waitForStart("wait-inside-1/" + (i + 1), rNext);
      isPaused(rNext, 1, 0);
      nextRuns.add(rNext);
      toUnlock = rNext;
    }
    SemaphoreStep.success("wait-inside-1/" + nextRuns.size(), null);
    waitAndClear(1, nextRuns);
  }

  @Issue("JENKINS-34433")
  @Test
  public void manualUnreserveUnblocksJob() throws Exception {
    LockableResourcesManager.get().createResource("resource1");
    JenkinsRule.WebClient wc = j.createWebClient();

    clickButton(wc, "reserve");
    LockableResource resource1 = LockableResourcesManager.get().fromName("resource1");
    assertNotNull(resource1);
    resource1.setReservedBy("someone");
    assertTrue(resource1.isReserved());

    JSONObject apiRes = TestHelpers.getResourceFromApi(j, "resource1", false);
    assertThat(apiRes, hasEntry("reserved", true));
    assertThat(apiRes, hasEntry("reservedBy", "someone"));

    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "retry(99) {\n"
                + "    lock('resource1') {\n"
                + "        semaphore('wait-inside')\n"
                + "     }\n"
                + "}",
            true));

    WorkflowRun r = p.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[resource1] is locked, waiting...", r);
    clickButton(wc, "unreserve");
    SemaphoreStep.waitForStart("wait-inside/1", r);
    SemaphoreStep.success("wait-inside/1", null);
    j.assertBuildStatusSuccess(j.waitForCompletion(r));
  }

  private void waitAndClear(int semaphoreIndex, List<WorkflowRun> nextRuns) throws Exception {
    WorkflowRun toClear = nextRuns.get(0);

    System.err.println("Waiting for semaphore to start for " + toClear.getNumber());
    SemaphoreStep.waitForStart("wait-inside-2/" + semaphoreIndex, toClear);

    List<WorkflowRun> remainingRuns = new ArrayList<>();

    if (nextRuns.size() > 1) {
      remainingRuns.addAll(nextRuns.subList(1, nextRuns.size()));

      for (WorkflowRun r : remainingRuns) {
        System.err.println("Verifying no semaphore yet for " + r.getNumber());
        j.assertLogNotContains("Entering semaphore now", r);
      }
    }

    SemaphoreStep.success("wait-inside-2/" + semaphoreIndex, null);
    System.err.println("Waiting for " + toClear.getNumber() + " to complete");
    j.assertBuildStatusSuccess(j.waitForCompletion(toClear));

    if (!remainingRuns.isEmpty()) {
      waitAndClear(semaphoreIndex + 1, remainingRuns);
    }
  }

  @Test
  @WithPlugin("jobConfigHistory.hpi")
  public void lockWithLabelConcurrent() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
    final WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "import java.util.Random; \n"
                + "Random random = new Random(0);\n"
                + "lock(label: 'label1') {\n"
                + "  echo 'Resource locked'\n"
                + "  sleep random.nextInt(10)*100\n"
                + "}\n"
                + "echo 'Finish'",
            true));
    final CyclicBarrier barrier = new CyclicBarrier(51);
    for (int i = 0; i < 50; i++) {
      Thread thread =
          new Thread() {
            @Override
            public void run() {
              try {
                barrier.await();
                p.scheduleBuild2(0).waitForStart();
              } catch (Exception e) {
                System.err.println("Failed to start pipeline job");
              }
            }
          };
      thread.start();
    }
    barrier.await();
    j.waitUntilNoActivity();
  }

  @Test
  public void lockMultipleResources() throws Exception {
    LockableResourcesManager.get().createResource("resource1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock(resource: 'resource1', extra: [[resource: 'resource2']]) {\n"
                + "	semaphore 'wait-inside'\n"
                + "}\n"
                + "echo 'Finish'",
            true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
    p2.setDefinition(
        new CpsFlowDefinition(
            "lock('resource1') {\n" + "	semaphore 'wait-inside-p2'\n" + "}\n" + "echo 'Finish'",
            true));
    WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b2);
    isPaused(b2, 1, 1);

    WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
    p3.setDefinition(
        new CpsFlowDefinition(
            "lock('resource2') {\n" + "	semaphore 'wait-inside-p3'\n" + "}\n" + "echo 'Finish'",
            true));
    WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[resource2] is locked by " + b1.getFullDisplayName() + ", waiting...", b3);
    isPaused(b3, 1, 1);

    // Unlock resources
    SemaphoreStep.success("wait-inside/1", null);
    j.waitForMessage("Lock released on resource [{resource1},{resource2}]", b1);
    isPaused(b1, 1, 0);

    // Both get their lock
    j.waitForMessage("Lock acquired on [resource1]", b2);
    j.waitForMessage("Lock acquired on [resource2]", b3);

    SemaphoreStep.success("wait-inside-p2/1", null);
    SemaphoreStep.success("wait-inside-p3/1", null);
    j.waitForMessage("Finish", b2);
    j.waitForMessage("Finish", b3);
    isPaused(b2, 1, 0);
    isPaused(b3, 1, 0);
  }

  @Test
  public void lockWithLabelAndResource() throws Exception {
    LockableResourcesManager.get().createResource("resource1");
    LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
    LockableResourcesManager.get().createResourceWithLabel("resource3", "label1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1', extra: [[resource: 'resource1']]) {\n"
                + "	semaphore 'wait-inside'\n"
                + "}\n"
                + "echo 'Finish'",
            true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
    p2.setDefinition(
        new CpsFlowDefinition(
            "lock('resource1') {\n" + "	semaphore 'wait-inside-p2'\n" + "}\n" + "echo 'Finish'",
            true));
    WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b2);
    isPaused(b2, 1, 1);

    WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
    p3.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1') {\n" + "	semaphore 'wait-inside-p3'\n" + "}\n" + "echo 'Finish'",
            true));
    WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[Label: label1] is locked, waiting...", b3);
    isPaused(b3, 1, 1);

    // Unlock resources
    SemaphoreStep.success("wait-inside/1", null);
    j.waitForMessage("Lock released on resource [{Label: label1},{resource1}]", b1);
    isPaused(b2, 1, 0);

    // Both get their lock
    j.waitForMessage("Lock acquired on [resource1]", b2);
    j.waitForMessage("Lock acquired on [Label: label1]", b3);

    SemaphoreStep.success("wait-inside-p2/1", null);
    SemaphoreStep.success("wait-inside-p3/1", null);
    j.waitForMessage("Finish", b2);
    j.waitForMessage("Finish", b3);
    isPaused(b2, 1, 0);
    isPaused(b3, 1, 0);
  }

  @Test
  public void lockWithLabelAndLabeledResource() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
    LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
    LockableResourcesManager.get().createResourceWithLabel("resource3", "label1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1', extra: [[resource: 'resource1']]) {\n"
                + "	semaphore 'wait-inside'\n"
                + "}\n"
                + "echo 'Finish'",
            true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
    p2.setDefinition(
        new CpsFlowDefinition(
            "lock('resource1') {\n" + "	semaphore 'wait-inside-p2'\n" + "}\n" + "echo 'Finish'",
            true));
    WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b2);
    isPaused(b2, 1, 1);

    WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
    p3.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1') {\n" + "	semaphore 'wait-inside-p3'\n" + "}\n" + "echo 'Finish'",
            true));
    WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[Label: label1] is locked, waiting...", b3);
    isPaused(b3, 1, 1);

    // Unlock resources
    SemaphoreStep.success("wait-inside/1", null);
    j.waitForMessage("Lock released on resource [{Label: label1},{resource1}]", b1);
    isPaused(b1, 1, 0);

    // #2 gets the lock before #3 (in the order as they requested the lock)
    j.waitForMessage("Lock acquired on [resource1]", b2);
    SemaphoreStep.success("wait-inside-p2/1", null);
    j.waitForMessage("Finish", b2);
    isPaused(b2, 1, 0);
    j.waitForMessage("Lock acquired on [Label: label1]", b3);
    SemaphoreStep.success("wait-inside-p3/1", null);
    j.waitForMessage("Finish", b3);
    isPaused(b3, 1, 0);

    assertNotNull(LockableResourcesManager.get().fromName("resource1"));
    assertNotNull(LockableResourcesManager.get().fromName("resource2"));
    assertNotNull(LockableResourcesManager.get().fromName("resource3"));
  }

  @Test
  public void lockWithLabelAndLabeledResourceQuantity() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
    LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
    LockableResourcesManager.get().createResourceWithLabel("resource3", "label1");
    LockableResourcesManager.get().createResourceWithLabel("resource4", "label1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock(variable: 'var', extra: [[resource: 'resource4'], [resource: 'resource2'], [label: 'label1', quantity: 2]]) {\n"
                + "  def lockedResources = env.var.split(',').sort()\n"
                + "  echo \"Resources locked: ${lockedResources}\"\n"
                + "  semaphore 'wait-inside'\n"
                + "}\n"
                + "echo 'Finish'",
            true));
    // #1 should lock as few resources as possible
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
    p2.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1', variable: 'var', quantity: 3) {\n"
                + "	def lockedResources = env.var.split(',').sort()\n"
                + "	echo \"Resources locked: ${lockedResources}\"\n"
                + "	semaphore 'wait-inside-quantity3'\n"
                + "}\n"
                + "echo 'Finish'",
            true));
    WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[Label: label1, Quantity: 3] is locked, waiting...", b2);
    j.waitForMessage("Found 2 available resource(s). Waiting for correct amount: 3.", b2);
    isPaused(b2, 1, 1);

    WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
    p3.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1', variable: 'var', quantity: 2) {\n"
                + "	def lockedResources = env.var.split(',').sort()\n"
                + "	echo \"Resources locked: ${lockedResources}\"\n"
                + "	semaphore 'wait-inside-quantity2'\n"
                + "}\n"
                + "echo 'Finish'",
            true));
    WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
    // While 2 continues waiting, 3 can continue directly
    SemaphoreStep.waitForStart("wait-inside-quantity2/1", b3);
    // Let 3 finish
    SemaphoreStep.success("wait-inside-quantity2/1", null);
    j.waitForMessage("Finish", b3);
    j.assertLogContains("Resources locked: [resource1, resource3]", b3);
    isPaused(b3, 1, 0);

    // Unlock resources
    SemaphoreStep.success("wait-inside/1", null);
    j.waitForMessage(
        "Lock released on resource [{resource4},{resource2},{Label: label1, Quantity: 2}]", b1);
    j.assertLogContains("Resources locked: [resource2, resource4]", b1);
    isPaused(b1, 1, 0);

    // #2 gets the lock
    j.waitForMessage("Lock acquired on [Label: label1, Quantity: 3]", b2);
    SemaphoreStep.success("wait-inside-quantity3/1", null);
    j.waitForMessage("Finish", b2);
    // Could be any 3 resources, so just check the beginning of the message
    j.assertLogContains("Resources locked: [resource", b2);
    isPaused(b2, 1, 0);

    assertNotNull(LockableResourcesManager.get().fromName("resource1"));
    assertNotNull(LockableResourcesManager.get().fromName("resource2"));
    assertNotNull(LockableResourcesManager.get().fromName("resource3"));
    assertNotNull(LockableResourcesManager.get().fromName("resource4"));
  }

  @Test
  @Issue("JENKINS-50176")
  public void lockWithLabelFillsVariable() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1', variable: 'someVar') {\n"
                + "  semaphore 'wait-inside'\n"
                + "  echo \"VAR IS $env.someVar\"\n"
                + "}",
            true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
    p2.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1', variable: 'someVar2') {\n"
                + "  echo \"VAR2 IS $env.someVar2\"\n"
                + "}",
            true));
    WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
    j.waitForMessage("is locked, waiting...", b2);
    isPaused(b2, 1, 1);

    // Unlock resources
    SemaphoreStep.success("wait-inside/1", null);
    j.waitForMessage("Lock released on resource", b1);
    isPaused(b1, 1, 0);

    // Now job 2 should get and release the lock...
    j.waitForCompletion(b1);
    j.waitForCompletion(b2);
    isPaused(b2, 1, 0);

    // Variable should have been filled in both cases
    j.assertLogContains("VAR IS resource1", b1);
    j.assertLogContains("VAR2 IS resource1", b2);
  }

  @Test
  @Issue("JENKINS-50176")
  public void parallelLockWithLabelFillsVariable() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "parallel p1: {\n"
                + "  lock(label: 'label1', variable: 'someVar') {\n"
                + "    semaphore 'wait-inside'\n"
                + "    echo \"VAR IS $env.someVar\"\n"
                + "  }\n"
                + "},\n"
                + "p2: {\n"
                + "  semaphore 'wait-outside'\n"
                + "  lock(label: 'label1', variable: 'someVar2') {\n"
                + "    echo \"VAR2 IS $env.someVar2\"\n"
                + "  }\n"
                + "}",
            true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-outside/1", b1);
    SemaphoreStep.waitForStart("wait-inside/1", b1);
    SemaphoreStep.success("wait-outside/1", null);

    j.waitForMessage("is locked, waiting...", b1);
    isPaused(b1, 2, 1);

    // Unlock resources
    SemaphoreStep.success("wait-inside/1", null);
    j.waitForMessage("Lock released on resource", b1);
    isPaused(b1, 2, 0);

    // Now the second parallel branch should get and release the lock...
    j.waitForCompletion(b1);
    isPaused(b1, 2, 0);

    // Variable should have been filled in both cases
    j.assertLogContains("VAR IS resource1", b1);
    j.assertLogContains("VAR2 IS resource1", b1);
  }

  @Test
  @Issue("JENKINS-54541")
  public void unreserveSetsVariable() throws Exception {
    LockableResourcesManager lm = LockableResourcesManager.get();
    lm.createResourceWithLabel("resource1", "label1");
    lm.reserve(Arrays.asList(lm.fromName("resource1")), "test");

    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1', variable: 'someVar') {\n"
                + "  echo \"VAR IS $env.someVar\"\n"
                + "}",
            true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

    j.waitForMessage("is locked, waiting...", b1);
    lm.unreserve(Arrays.asList(lm.fromName("resource1")));
    j.assertBuildStatusSuccess(j.waitForCompletion(b1));

    // Variable should have been filled
    j.assertLogContains("VAR IS resource1", b1);
  }

  @Test
  public void lockWithInvalidLabel() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(new CpsFlowDefinition("lock(label: 'invalidLabel') {\n" + "}\n", true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);
    j.assertBuildStatus(Result.FAILURE, b1);
    j.waitUntilNoActivity();
    j.assertLogContains("The label does not exist: invalidLabel", b1);
    isPaused(b1, 0, 0);
  }

  @Test
  public void skipIfLocked() throws Exception {
    LockableResourcesManager lm = LockableResourcesManager.get();
    lm.createResourceWithLabel("resource1", "label1");
    lm.reserve(Arrays.asList(lm.fromName("resource1")), "test");

    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock(resource: 'resource1', skipIfLocked: true) {\n" + "  echo 'Running body'\n" + "}",
            true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);
    j.assertBuildStatus(Result.SUCCESS, b1);
    j.assertLogContains("[resource1] is locked, skipping execution...", b1);
    j.assertLogNotContains("Running body", b1);
  }
}
