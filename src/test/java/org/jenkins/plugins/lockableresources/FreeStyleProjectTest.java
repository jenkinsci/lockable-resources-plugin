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
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
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
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkinsci.plugins.scriptsecurity.scripts.ApprovalContext;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestBuilder;

public class FreeStyleProjectTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    // ---------------------------------------------------------------------------
    @Before
    public void setUp() {
        // to speed up the test
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    }

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
        p.addProperty(new RequiredResourcesProperty(null, null, null, "groovy:resourceName == 'resource1'", null));

        p.save();

        j.jenkins.reload();

        FreeStyleProject p2 = j.jenkins.getItemByFullName("p", FreeStyleProject.class);
        RequiredResourcesProperty newProp = p2.getProperty(RequiredResourcesProperty.class);
        assertNull(newProp.getLabelName());
        assertNotNull(newProp.getResourceMatchScript());
        assertEquals(
                "resourceName == 'resource1'", newProp.getResourceMatchScript().getScript());

        SemaphoreBuilder p2Builder = new SemaphoreBuilder();
        p2.getBuildersList().add(p2Builder);

        FreeStyleProject p3 = j.createFreeStyleProject("p3");
        p3.addProperty(new RequiredResourcesProperty("resource1", null, "1", null, null));
        SemaphoreBuilder p3Builder = new SemaphoreBuilder();
        p3.getBuildersList().add(p3Builder);

        final QueueTaskFuture<FreeStyleBuild> taskA = p3.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        TestHelpers.waitForQueue(j.jenkins, p3);
        final QueueTaskFuture<FreeStyleBuild> taskB = p2.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());

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
        withResource.addProperty(new RequiredResourcesProperty("resource1", "resourceNameVar", null, null, null));
        FreeStyleProject withResourceRoundTrip = j.configRoundtrip(withResource);

        RequiredResourcesProperty withResourceProp = withResourceRoundTrip.getProperty(RequiredResourcesProperty.class);
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

        RequiredResourcesProperty withLabelProp = withLabelRoundTrip.getProperty(RequiredResourcesProperty.class);
        assertNotNull(withLabelProp);
        assertNull(withLabelProp.getResourceNames());
        assertNull(withLabelProp.getResourceNamesVar());
        assertNull(withLabelProp.getResourceNumber());
        assertEquals("some-label", withLabelProp.getLabelName());
        assertNull(withLabelProp.getResourceMatchScript());
    }

    @Test
    @Issue("JENKINS-30308")
    public void configRoundTripWithParam() throws Exception {
        FreeStyleProject withParam = j.createFreeStyleProject("withparam");
        withParam.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("param1", "some-resource", "parameter 1")));
        withParam.addProperty(new RequiredResourcesProperty("${param1}", null, null, null, null));
        FreeStyleProject withParamRoundTrip = j.configRoundtrip(withParam);

        ParametersDefinitionProperty paramsProp = withParamRoundTrip.getProperty(ParametersDefinitionProperty.class);
        assertNotNull(paramsProp);

        RequiredResourcesProperty resourcesProp = withParamRoundTrip.getProperty(RequiredResourcesProperty.class);
        assertNotNull(resourcesProp);
        assertEquals("${param1}", resourcesProp.getResourceNames());
        assertNull(resourcesProp.getResourceNamesVar());
        assertNull(resourcesProp.getResourceNumber());
        assertNull(resourcesProp.getLabelName());
        assertNull(resourcesProp.getResourceMatchScript());
    }

    @Test
    public void configRoundTripWithLabelParam() throws Exception {
        FreeStyleProject withLabel = j.createFreeStyleProject("withLabelParam");
        withLabel.addProperty(new RequiredResourcesProperty(null, null, null, "${labelParam}", null));
        FreeStyleProject withLabelRoundTrip = j.configRoundtrip(withLabel);

        RequiredResourcesProperty withLabelProp = withLabelRoundTrip.getProperty(RequiredResourcesProperty.class);
        assertNotNull(withLabelProp);
        assertNull(withLabelProp.getResourceNames());
        assertNull(withLabelProp.getResourceNamesVar());
        assertNull(withLabelProp.getResourceNumber());
        assertEquals("${labelParam}", withLabelProp.getLabelName());
        assertNull(withLabelProp.getResourceMatchScript());
    }

    @Test
    public void configRoundTripWithNumParam() throws Exception {
        FreeStyleProject withNum = j.createFreeStyleProject("withNumParam");
        withNum.addProperty(new RequiredResourcesProperty(null, null, "${resNum}", "some-resources", null));
        FreeStyleProject withNumRoundTrip = j.configRoundtrip(withNum);

        RequiredResourcesProperty withNumProp = withNumRoundTrip.getProperty(RequiredResourcesProperty.class);
        assertNotNull(withNumProp);
        assertNull(withNumProp.getResourceNames());
        assertNull(withNumProp.getResourceNamesVar());
        assertEquals("${resNum}", withNumProp.getResourceNumber());
        assertEquals("some-resources", withNumProp.getLabelName());
        assertNull(withNumProp.getResourceMatchScript());
    }

    @Test
    public void configRoundTripWithScript() throws Exception {
        FreeStyleProject withScript = j.createFreeStyleProject("withScript");
        SecureGroovyScript origScript = new SecureGroovyScript("return true", false, null);
        withScript.addProperty(new RequiredResourcesProperty(null, null, null, null, origScript));
        FreeStyleProject withScriptRoundTrip = j.configRoundtrip(withScript);

        RequiredResourcesProperty withScriptProp = withScriptRoundTrip.getProperty(RequiredResourcesProperty.class);
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

        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
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
                "resourceName == " + "org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction.ICON;";

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
        List<ScriptApproval.PendingSignature> pending = new ArrayList<>(approval.getPendingSignatures());

        assertFalse(pending.isEmpty());
        assertEquals(1, pending.size());
        ScriptApproval.PendingSignature firstPending = pending.get(0);

        assertEquals(
                "staticField org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction " + "ICON",
                firstPending.signature);
        approval.approveSignature(firstPending.signature);

        j.assertBuildStatusSuccess(futureBuild);
    }

    @Test
    public void autoCreateResource() throws IOException, InterruptedException, ExecutionException {
        FreeStyleProject f = j.createFreeStyleProject("f");
        assertNull(LockableResourcesManager.get().fromName("resource1"));
        f.addProperty(new RequiredResourcesProperty("resource1", null, null, null, null));
        assertNull(LockableResourcesManager.get().fromName("resource1"));

        FreeStyleBuild fb1 = f.scheduleBuild2(0).waitForStart();
        j.waitForMessage("acquired lock on [resource1]", fb1);
        j.waitForCompletion(fb1);

        assertNull(LockableResourcesManager.get().fromName("resource1"));
    }

    @Test
    @Issue("JENKINS-30308")
    public void autoCreateResourceFromParameter() throws Exception {
        ParametersDefinitionProperty params =
                new ParametersDefinitionProperty(new StringParameterDefinition("param1", "resource1", "parameter 1"));

        FreeStyleProject f = j.createFreeStyleProject("f");
        f.addProperty(params);
        f.addProperty(new RequiredResourcesProperty("${param1}", null, null, null, null));

        FreeStyleBuild fb1 = f.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(fb1);
        assertEquals("resource1", fb1.getBuildVariableResolver().resolve("param1"));
        j.assertLogContains("acquired lock on [resource1]", fb1);
        j.waitUntilNoActivity();

        assertNull(LockableResourcesManager.get().fromName("resource1"));
    }

    @Test
    @Issue("JENKINS-30308")
    public void parallelResourceFromParameter() throws Exception {
        LockableResourcesManager lm = LockableResourcesManager.get();
        lm.createResource("resource1");
        lm.createResource("resource2");
        lm.createResource("resource3");

        StringParameterDefinition param1Def = new StringParameterDefinition("param1", "", "parameter 1");

        FreeStyleProject f = j.createFreeStyleProject("f");
        f.setConcurrentBuild(true);
        f.addProperty(new ParametersDefinitionProperty(param1Def));
        f.addProperty(new RequiredResourcesProperty("${param1}", null, null, null, null));
        f.getBuildersList().add(new WaitBuilder());

        List<ParameterValue> values1 = new ArrayList<ParameterValue>();
        values1.add(param1Def.createValue("resource1"));
        FreeStyleBuild fb1 = f.scheduleBuild2(0, new ParametersAction(values1)).waitForStart();
        j.waitForMessage("acquired lock on [resource1]", fb1);
        j.waitForMessage("Waiting...", fb1);
        j.assertLogNotContains("Continue", fb1);
        Thread.sleep(100);

        List<ParameterValue> values2 = new ArrayList<ParameterValue>();
        values2.add(param1Def.createValue("resource2"));
        FreeStyleBuild fb2 = f.scheduleBuild2(0, new ParametersAction(values2)).waitForStart();
        j.waitForMessage("acquired lock on [resource2]", fb2);
        j.waitForMessage("Waiting...", fb2);
        j.assertLogNotContains("Continue", fb2);

        List<ParameterValue> values3 = new ArrayList<ParameterValue>();
        values3.add(param1Def.createValue("resource1"));
        QueueTaskFuture<FreeStyleBuild> qt3 = f.scheduleBuild2(0, new ParametersAction(values3));
        TestHelpers.waitForQueue(j.jenkins, f, Queue.BlockedItem.class);

        Queue.BlockedItem blockedItem = (Queue.BlockedItem) j.jenkins.getQueue().getItem(f);
        assertThat(
                blockedItem.getCauseOfBlockage(),
                is(instanceOf(LockableResourcesQueueTaskDispatcher.BecauseResourcesLocked.class)));

        synchronized (fb2) {
            fb2.notifyAll();
        }
        Thread.sleep(100);

        blockedItem = (Queue.BlockedItem) j.jenkins.getQueue().getItem(f);
        assertThat(
                blockedItem.getCauseOfBlockage(),
                is(instanceOf(LockableResourcesQueueTaskDispatcher.BecauseResourcesLocked.class)));

        j.assertLogNotContains("Continue", fb1);
        synchronized (fb1) {
            fb1.notifyAll();
        }
        j.waitForMessage("Continue", fb1);
        j.waitForMessage("released lock on [resource1]", fb1);
        j.waitForCompletion(fb1);

        FreeStyleBuild fb3 = qt3.waitForStart();
        j.waitForMessage("acquired lock on [resource1]", fb3);
        j.waitForMessage("Waiting...", fb3);
        j.assertLogNotContains("Continue", fb3);
        synchronized (fb3) {
            fb3.notifyAll();
        }

        j.waitUntilNoActivity();
        assertTrue(
                String.format(
                        "#1 build should be started before the build of #2. #1 started at %d, #2 finished at %d",
                        fb1.getStartTimeInMillis(), fb2.getStartTimeInMillis()),
                fb1.getStartTimeInMillis() < fb2.getStartTimeInMillis());

        long fb1EndTime = fb1.getStartTimeInMillis() + fb1.getDuration();
        long fb2EndTime = fb2.getStartTimeInMillis() + fb2.getDuration();
        assertTrue(
                String.format(
                        "#2 build should be finished before the build of #1. #1 finished at %d, #2 finished at %d",
                        fb1EndTime, fb2EndTime),
                fb2EndTime < fb1EndTime);
        assertTrue(
                String.format(
                        "#3 build should be started after the build of #1. #1 finished at %d, #3 started at %d",
                        fb1EndTime, fb3.getStartTimeInMillis()),
                fb1EndTime < fb3.getStartTimeInMillis());
    }

    @Test
    public void labelFromParameter() throws IOException, InterruptedException, ExecutionException {
        LockableResourcesManager lm = LockableResourcesManager.get();
        lm.createResourceWithLabel("resource1", "resource");
        lm.createResourceWithLabel("resource2", "resource");

        ParametersDefinitionProperty params = new ParametersDefinitionProperty(
                new StringParameterDefinition("labelParam", "resource", "parameter 1"));

        FreeStyleProject f = j.createFreeStyleProject("f");
        f.addProperty(params);
        f.addProperty(new RequiredResourcesProperty(null, null, null, "${labelParam}", null));

        FreeStyleBuild fb1 = f.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(fb1);
        assertEquals("resource", fb1.getBuildVariableResolver().resolve("labelParam"));
        j.assertLogContains("acquired lock on [resource1, resource2]", fb1);
    }

    @Test
    public void resourceNumberFromParameter() throws IOException, InterruptedException, ExecutionException {
        LockableResourcesManager lm = LockableResourcesManager.get();
        lm.createResourceWithLabel("resource1", "resource");
        lm.createResourceWithLabel("resource2", "resource");
        lm.createResourceWithLabel("resource3", "resource");
        lm.reserve(List.of(lm.fromName("resource1")), "user1");

        ParametersDefinitionProperty params =
                new ParametersDefinitionProperty(new StringParameterDefinition("numParam", "2", "parameter 1"));

        FreeStyleProject f = j.createFreeStyleProject("f");
        f.addProperty(params);
        f.addProperty(new RequiredResourcesProperty(null, null, "${numParam}", "resource", null));

        FreeStyleBuild fb1 = f.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(fb1);
        assertEquals("2", fb1.getBuildVariableResolver().resolve("numParam"));
        j.assertLogContains("acquired lock on [resource2, resource3]", fb1);
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
        f0.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
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
            listener.getLogger()
                    .println(
                            "resourceNameVar: " + build.getEnvironment(listener).get("resourceNameVar"));
            return true;
        }
    }

    private static class WaitBuilder extends TestBuilder {

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException {
            listener.getLogger().println("Waiting...");

            synchronized (build) {
                build.wait();
            }
            listener.getLogger().println("Continue");
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
