package org.jenkins.plugins.lockableresources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.User;
import hudson.model.queue.QueueTaskFuture;
import hudson.triggers.TimerTrigger;
import hudson.util.OneShotEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesQueueTaskDispatcher;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkinsci.plugins.scriptsecurity.scripts.ApprovalContext;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestBuilder;

public class FreeStyleProjectTest {

  @Rule public JenkinsRule j = new JenkinsRule();

  @Test
  @Issue("JENKINS-34853")
  public void security170fix() throws Exception {
    LockableResourcesManager.get().createResource("resource1");
    FreeStyleProject p = j.createFreeStyleProject("p");
    p.addProperty(new RequiredResourcesProperty("resource1", "resourceNameVar", null, null, null));
    p.getBuildersList().add(new PrinterBuilder());

    FreeStyleBuild b1 = p.scheduleBuild2(0).get();
    j.assertLogContains("resourceNameVar: resource1", b1);
    j.assertBuildStatus(Result.SUCCESS, b1);
  }

  @Test
  public void migrateToScript() throws Exception {
    LockableResourcesManager.get().createResource("resource1");

    FreeStyleProject p = j.createFreeStyleProject("p");
    p.addProperty(
      new RequiredResourcesProperty(
        null, null, null, "groovy:resourceName == 'resource1'", null));

    p.save();

    j.jenkins.reload();

    FreeStyleProject p2 = j.jenkins.getItemByFullName("p", FreeStyleProject.class);
    RequiredResourcesProperty newProp = p2.getProperty(RequiredResourcesProperty.class);
    assertNull(newProp.getLabelName());
    assertNotNull(newProp.getResourceMatchScript());
    assertEquals("resourceName == 'resource1'", newProp.getResourceMatchScript().getScript());

    SemaphoreBuilder p2Builder = new SemaphoreBuilder();
    p2.getBuildersList().add(p2Builder);

    FreeStyleProject p3 = j.createFreeStyleProject("p3");
    p3.addProperty(new RequiredResourcesProperty("resource1", null, "1", null, null));
    SemaphoreBuilder p3Builder = new SemaphoreBuilder();
    p3.getBuildersList().add(p3Builder);

    final QueueTaskFuture<FreeStyleBuild> taskA =
      p3.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
    TestHelpers.waitForQueue(j.jenkins, p3);
    final QueueTaskFuture<FreeStyleBuild> taskB =
      p2.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());

    p3Builder.release();
    final FreeStyleBuild buildA = taskA.get(60, TimeUnit.SECONDS);
    p2Builder.release();
    final FreeStyleBuild buildB = taskB.get(60, TimeUnit.SECONDS);

    long buildAEndTime = buildA.getStartTimeInMillis() + buildA.getDuration();
    assertTrue(
      "Project A build should be finished before the build of project B starts. "
        + "A finished at "
        + buildAEndTime
        + ", B started at "
        + buildB.getStartTimeInMillis(),
      buildB.getStartTimeInMillis() >= buildAEndTime);
  }

  @Test
  public void configRoundTripPlain() throws Exception {
    LockableResourcesManager.get().createResource("resource1");

    FreeStyleProject withResource = j.createFreeStyleProject("withResource");
    withResource.addProperty(
      new RequiredResourcesProperty("resource1", "resourceNameVar", null, null, null));
    FreeStyleProject withResourceRoundTrip = j.configRoundtrip(withResource);

    RequiredResourcesProperty withResourceProp =
      withResourceRoundTrip.getProperty(RequiredResourcesProperty.class);
    assertNotNull(withResourceProp);
    assertEquals("resource1", withResourceProp.getResourceNames());
    assertEquals("resourceNameVar", withResourceProp.getResourceNamesVar());
    assertNull(withResourceProp.getResourceNumber());
    assertNull(withResourceProp.getLabelName());
    assertNull(withResourceProp.getResourceMatchScript());
  }

  @Test
  public void configRoundTripWithLabel() throws Exception {
    FreeStyleProject withLabel = j.createFreeStyleProject("withLabel");
    withLabel.addProperty(new RequiredResourcesProperty(null, null, null, "some-label", null));
    FreeStyleProject withLabelRoundTrip = j.configRoundtrip(withLabel);

    RequiredResourcesProperty withLabelProp =
      withLabelRoundTrip.getProperty(RequiredResourcesProperty.class);
    assertNotNull(withLabelProp);
    assertNull(withLabelProp.getResourceNames());
    assertNull(withLabelProp.getResourceNamesVar());
    assertNull(withLabelProp.getResourceNumber());
    assertEquals("some-label", withLabelProp.getLabelName());
    assertNull(withLabelProp.getResourceMatchScript());
  }

  @Test
  public void configRoundTripWithScript() throws Exception {
    FreeStyleProject withScript = j.createFreeStyleProject("withScript");
    SecureGroovyScript origScript = new SecureGroovyScript("return true", false, null);
    withScript.addProperty(new RequiredResourcesProperty(null, null, null, null, origScript));
    FreeStyleProject withScriptRoundTrip = j.configRoundtrip(withScript);

    RequiredResourcesProperty withScriptProp =
      withScriptRoundTrip.getProperty(RequiredResourcesProperty.class);
    assertNotNull(withScriptProp);
    assertNull(withScriptProp.getResourceNames());
    assertNull(withScriptProp.getResourceNamesVar());
    assertNull(withScriptProp.getResourceNumber());
    assertNull(withScriptProp.getLabelName());
    assertNotNull(withScriptProp.getResourceMatchScript());
    assertEquals("return true", withScriptProp.getResourceMatchScript().getScript());
    assertFalse(withScriptProp.getResourceMatchScript().isSandbox());
  }

  @Test
  public void approvalRequired() throws Exception {
    LockableResourcesManager.get().createResource(LockableResourcesRootAction.ICON);

    j.jenkins.setSecurityRealm(j.createDummySecurityRealm());

    j.jenkins.setAuthorizationStrategy(
      new MockAuthorizationStrategy()
        .grant(Jenkins.READ, Item.READ)
        .everywhere()
        .toAuthenticated()
        .grant(Jenkins.ADMINISTER)
        .everywhere()
        .to("bob")
        .grant(Item.CONFIGURE, Item.BUILD)
        .everywhere()
        .to("alice"));

    final String SCRIPT =
      "resourceName == "
        + "org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction.ICON;";

    FreeStyleProject p = j.createFreeStyleProject();
    SecureGroovyScript groovyScript =
      new SecureGroovyScript(SCRIPT, true, null).configuring(ApprovalContext.create());

    p.addProperty(new RequiredResourcesProperty(null, null, null, null, groovyScript));

    User.getOrCreateByIdOrFullName("alice");
    JenkinsRule.WebClient wc = j.createWebClient();
    wc.login("alice");

    QueueTaskFuture<FreeStyleBuild> futureBuild = p.scheduleBuild2(0);
    TestHelpers.waitForQueue(j.jenkins, p, Queue.BlockedItem.class);

    Queue.BlockedItem blockedItem = (Queue.BlockedItem) j.jenkins.getQueue().getItem(p);
    assertThat(
      blockedItem.getCauseOfBlockage(),
      is(instanceOf(LockableResourcesQueueTaskDispatcher.BecauseResourcesQueueFailed.class)));

    ScriptApproval approval = ScriptApproval.get();
    List<ScriptApproval.PendingSignature> pending =
      new ArrayList<>(approval.getPendingSignatures());

    assertFalse(pending.isEmpty());
    assertEquals(1, pending.size());
    ScriptApproval.PendingSignature firstPending = pending.get(0);

    assertEquals(
      "staticField org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction "
        + "ICON",
      firstPending.signature);
    approval.approveSignature(firstPending.signature);

    j.assertBuildStatusSuccess(futureBuild);
  }

  @Test
  public void autoCreateResource() throws IOException, InterruptedException, ExecutionException {
    FreeStyleProject f = j.createFreeStyleProject("f");
    f.addProperty(new RequiredResourcesProperty("resource1", null, null, null, null));

    FreeStyleBuild fb1 = f.scheduleBuild2(0).waitForStart();
    j.waitForMessage("acquired lock on [resource1]", fb1);
    j.waitForCompletion(fb1);

    assertNull(LockableResourcesManager.get().fromName("resource1"));
  }

  @Test
  public void competingLabelLocks() throws IOException, InterruptedException, ExecutionException {
    LockableResourcesManager.get().createResourceWithLabel("resource1", "group1");
    LockableResourcesManager.get().createResourceWithLabel("resource2", "group2");
    LockableResourcesManager.get().createResource("shared");
    LockableResource shared = LockableResourcesManager.get().fromName("shared");
    shared.setEphemeral(false);
    shared.getLabelsAsList().addAll(List.of("group1", "group2"));
    FreeStyleProject f0 = j.createFreeStyleProject("f0");
    final Semaphore semaphore = new Semaphore(1);
    f0.addProperty(new RequiredResourcesProperty("shared", null, null, null, null));
    f0.getBuildersList()
      .add(
        new TestBuilder() {
          @Override
          public boolean perform(
            AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException {
            semaphore.acquire();
            return true;
          }
        });
    FreeStyleProject f1 = j.createFreeStyleProject("f1");
    f1.addProperty(new RequiredResourcesProperty(null, null, "0", "group1", null));
    FreeStyleProject f2 = j.createFreeStyleProject("f2");
    f2.addProperty(new RequiredResourcesProperty(null, null, "0", "group2", null));


    semaphore.acquire();
    FreeStyleBuild fb0 = f0.scheduleBuild2(0).waitForStart();
    j.waitForMessage("acquired lock on [shared]", fb0);
    QueueTaskFuture<FreeStyleBuild> fb1q = f1.scheduleBuild2(0);
    QueueTaskFuture<FreeStyleBuild> fb2q = f2.scheduleBuild2(0);

    semaphore.release();
    j.waitForCompletion(fb0);
    // fb1 or fb2 might run first, it shouldn't matter as long as they both get the resource
    FreeStyleBuild fb1 = fb1q.waitForStart();
    FreeStyleBuild fb2 = fb2q.waitForStart();
    j.waitForMessage("acquired lock on [resource1, shared]", fb1);
    j.waitForCompletion(fb1);
    j.waitForMessage("acquired lock on [resource2, shared]", fb2);
    j.waitForCompletion(fb2);
  }

  public static class PrinterBuilder extends TestBuilder {

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
      throws InterruptedException, IOException {
      listener
        .getLogger()
        .println("resourceNameVar: " + build.getEnvironment(listener).get("resourceNameVar"));
      return true;
    }
  }

  private static class SemaphoreBuilder extends TestBuilder {

    private final OneShotEvent event = new OneShotEvent();

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
      throws InterruptedException {
      event.block();
      return true;
    }

    void release() {
      event.signal();
    }
  }
}
