package org.jenkins.plugins.lockableresources;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import hudson.Functions;
import hudson.Launcher;
import hudson.model.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.recipes.WithPlugin;

public class LockStepTest extends LockStepTestBase {

  @Rule public JenkinsRule j = new JenkinsRule();

  @Test
  public void autoCreateResource() throws Exception {
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock('resource1') {\n" + "	echo 'Resource locked'\n" + "}\n" + "echo 'Finish'"));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);
    j.assertBuildStatus(Result.SUCCESS, b1);
    j.assertLogContains("Resource [resource1] did not exist. Created.", b1);

    assertNull(LockableResourcesManager.get().fromName("resource1"));
  }

  @Test
  public void autoCreateResourceFreeStyle() throws IOException, InterruptedException {
    FreeStyleProject f = j.createFreeStyleProject("f");
    f.addProperty(new RequiredResourcesProperty("resource1", null, null, null, null));

    f.scheduleBuild2(0);

    while (j.jenkins.getQueue().getItems().length != 1) {
      System.out.println("Waiting for freestyle to be queued...");
      Thread.sleep(1000);
    }

    FreeStyleBuild fb1 = null;
    while ((fb1 = f.getBuildByNumber(1)) == null) {
      System.out.println("Waiting for freestyle #1 to start building...");
      Thread.sleep(1000);
    }

    j.waitForMessage("acquired lock on [resource1]", fb1);
    j.waitForCompletion(fb1);

    assertNull(LockableResourcesManager.get().fromName("resource1"));
  }

  @Test
  public void lockNothing() throws Exception {
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock() {\n" + "  echo 'Nothing locked.'\n" + "}\n" + "echo 'Finish'"));
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
                + "echo 'Finish'"));
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
                + "echo 'Finish'"));
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
                + "echo 'Finish'"));
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
                + "echo 'Finish'"));
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
            "lock(label: 'label1') {\n" + "	semaphore 'wait-inside'\n" + "}\n" + "echo 'Finish'"));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
    p2.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1', quantity: 2) {\n"
                + "	semaphore 'wait-inside-quantity2'\n"
                + "}\n"
                + "echo 'Finish'"));
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
                + "echo 'Finish'"));
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
            "lock('resource1') {\n" + "	semaphore 'wait-inside'\n" + "}\n" + "echo 'Finish'"));
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
                + "echo 'Finish'"));
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
    LockableResourcesManager.get().createResource("resource1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "parallel a: {\n"
                + "	sleep 5\n"
                + "	lock('resource1') {\n"
                + "		sleep 5\n"
                + "	}\n"
                + "}, b: {\n"
                + "	lock('resource1') {\n"
                + "		semaphore 'wait-b'\n"
                + "	}\n"
                + "}\n"));

    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-b/1", b1);
    // both messages are in the log because branch b acquired the lock and branch a is waiting to
    // lock
    j.waitForMessage("[b] Lock acquired on [resource1]", b1);
    j.waitForMessage(
        "[a] [resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b1);
    isPaused(b1, 2, 1);

    SemaphoreStep.success("wait-b/1", null);

    j.waitForMessage("[a] Lock acquired on [resource1]", b1);
    isPaused(b1, 2, 0);

    assertNotNull(LockableResourcesManager.get().fromName("resource1"));
  }

  @Test
  public void interoperability() throws Exception {
    final Semaphore semaphore = new Semaphore(1);
    LockableResourcesManager.get().createResource("resource1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock('resource1') {\n" + "	echo 'Locked'\n" + "}\n" + "echo 'Finish'"));

    FreeStyleProject f = j.createFreeStyleProject("f");
    f.addProperty(new RequiredResourcesProperty("resource1", null, null, null, null));
    f.getBuildersList()
        .add(
            new TestBuilder() {

              @Override
              public boolean perform(
                  AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                  throws InterruptedException, IOException {
                semaphore.acquire();
                return true;
              }
            });
    semaphore.acquire();
    FreeStyleBuild f1 = f.scheduleBuild2(0).waitForStart();

    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[resource1] is locked by " + f1.getFullDisplayName() + ", waiting...", b1);
    isPaused(b1, 1, 1);
    semaphore.release();

    // Wait for lock after the freestyle finishes
    j.waitForMessage("Lock released on resource [resource1]", b1);
    isPaused(b1, 1, 0);
  }

  // TODO: Figure out what to do about the IOException thrown during clean up, since we don't care
  // about it. It's just
  // a result of the first build being deleted and is nothing but noise here.
  @Issue("JENKINS-36479")
  @Test
  public void deleteRunningBuildNewBuildClearsLock() throws Exception {
    assumeFalse(Functions.isWindows()); // TODO: Investigate failure on Windows.

    LockableResourcesManager.get().createResource("resource1");

    WorkflowJob p1 = j.jenkins.createProject(WorkflowJob.class, "p");
    p1.setDefinition(
        new CpsFlowDefinition("lock('resource1') { echo 'locked!'; semaphore 'wait-inside' }"));
    WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
    j.waitForMessage("locked!", b1);
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
    p2.setDefinition(
        new CpsFlowDefinition("lock('resource1') {\n" + "  semaphore 'wait-inside'\n" + "}"));
    WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();

    // Now b2 is still sitting waiting for a lock. Create b3 and launch it to clear the lock.
    WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
    p3.setDefinition(
        new CpsFlowDefinition("lock('resource1') {\n" + "  semaphore 'wait-inside'\n" + "}"));
    WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();

    // Make sure that b2 is blocked on b1's lock.
    j.waitForMessage("[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b2);
    isPaused(b2, 1, 1);
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
        wc.goTo("lockable-resources/unlock?resource=resource1");
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

    wc.goTo("lockable-resources/reserve?resource=resource1");
    LockableResource resource1 = LockableResourcesManager.get().fromName("resource1");
    assertNotNull(resource1);
    resource1.setReservedBy("someone");
    assertTrue(resource1.isReserved());

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
    wc.goTo("lockable-resources/unreserve?resource=resource1");
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
                + "echo 'Finish'"));
    final CyclicBarrier barrier = new CyclicBarrier(51);
    for (int i = 0; i < 50; i++) {
      Thread thread =
          new Thread() {
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
                + "echo 'Finish'"));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
    p2.setDefinition(
        new CpsFlowDefinition(
            "lock('resource1') {\n" + "	semaphore 'wait-inside-p2'\n" + "}\n" + "echo 'Finish'"));
    WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b2);
    isPaused(b2, 1, 1);

    WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
    p3.setDefinition(
        new CpsFlowDefinition(
            "lock('resource2') {\n" + "	semaphore 'wait-inside-p3'\n" + "}\n" + "echo 'Finish'"));
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
                + "echo 'Finish'"));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
    p2.setDefinition(
        new CpsFlowDefinition(
            "lock('resource1') {\n" + "	semaphore 'wait-inside-p2'\n" + "}\n" + "echo 'Finish'"));
    WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b2);
    isPaused(b2, 1, 1);

    WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
    p3.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1') {\n"
                + "	semaphore 'wait-inside-p3'\n"
                + "}\n"
                + "echo 'Finish'"));
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
                + "echo 'Finish'"));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
    p2.setDefinition(
        new CpsFlowDefinition(
            "lock('resource1') {\n" + "	semaphore 'wait-inside-p2'\n" + "}\n" + "echo 'Finish'"));
    WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[resource1] is locked by " + b1.getFullDisplayName() + ", waiting...", b2);
    isPaused(b2, 1, 1);

    WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
    p3.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1') {\n"
                + "	semaphore 'wait-inside-p3'\n"
                + "}\n"
                + "echo 'Finish'"));
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
                + "  def lockedResources = env.var.split(',')\n"
                + "  Arrays.sort(lockedResources)\n"
                + "  echo \"Resources locked: ${lockedResources}\"\n"
                + "  semaphore 'wait-inside'\n"
                + "}\n"
                + "echo 'Finish'"));
    // #1 should lock as few resources as possible
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    SemaphoreStep.waitForStart("wait-inside/1", b1);

    WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
    p2.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1', variable: 'var', quantity: 3) {\n"
                + "	def lockedResources = env.var.split(',')\n"
                + "	Arrays.sort(lockedResources)\n"
                + "	echo \"Resources locked: ${lockedResources}\"\n"
                + "	semaphore 'wait-inside-quantity3'\n"
                + "}\n"
                + "echo 'Finish'"));
    WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[Label: label1, Quantity: 3] is locked, waiting...", b2);
    j.waitForMessage("Found 2 available resource(s). Waiting for correct amount: 3.", b2);
    isPaused(b2, 1, 1);

    WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
    p3.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'label1', variable: 'var', quantity: 2) {\n"
                + "	def lockedResources = env.var.split(',')\n"
                + "	Arrays.sort(lockedResources)\n"
                + "	echo \"Resources locked: ${lockedResources}\"\n"
                + "	semaphore 'wait-inside-quantity2'\n"
                + "}\n"
                + "echo 'Finish'"));
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
  public void lockWithInvalidLabel() throws Exception {
    LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock(label: 'invalidLabel', variable: 'var', quantity: 1) {\n"
                + "	echo \"Resource locked: ${env.var}\"\n"
                + "}\n"
                + "echo 'Finish'"));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForCompletion(b1);
    j.assertBuildStatus(Result.FAILURE, b1);
    j.assertLogContains("The label does not exist: invalidLabel", b1);
    isPaused(b1, 0, 0);
  }
}
