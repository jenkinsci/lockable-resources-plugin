package org.jenkins.plugins.lockableresources;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Queue;
import hudson.model.User;
import hudson.model.queue.QueueTaskFuture;
import hudson.security.ACL;
import hudson.triggers.TimerTrigger;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
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
import org.jvnet.hudson.test.MockBuilder;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.TestExtension;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BasicIntegrationTest {

	@Rule
	public JenkinsRule j = new JenkinsRule();

	@Test
	@Issue("JENKINS-34853")
	public void security170fix() throws Exception {
		LockableResourcesManager.get().createResource("resource1");
		FreeStyleProject p = j.createFreeStyleProject("p");
		p.addProperty(new RequiredResourcesProperty("resource1", "resourceNameVar", null, null));
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
		assertEquals("resourceName == 'resource1'", newProp.getResourceMatchScript().getScript());

		p2.getBuildersList().add(new SleepBuilder(5000));

		FreeStyleProject p3 = j.createFreeStyleProject("p3");
		p3.addProperty(new RequiredResourcesProperty("resource1", null, "1", null, null));
		p3.getBuildersList().add(new SleepBuilder(10000));

		final QueueTaskFuture<FreeStyleBuild> taskA = p3.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
		Thread.sleep(2500);
		final QueueTaskFuture<FreeStyleBuild> taskB = p2.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());

		final FreeStyleBuild buildA = taskA.get(60, TimeUnit.SECONDS);
		final FreeStyleBuild buildB = taskB.get(60, TimeUnit.SECONDS);

		long buildAEndTime = buildA.getStartTimeInMillis() + buildA.getDuration();
		assertTrue("Project A build should be finished before the build of project B starts. " +
				"A finished at " + buildAEndTime + ", B started at " + buildB.getStartTimeInMillis(),
				buildB.getStartTimeInMillis() >= buildAEndTime);
	}

	@Test
	public void configRoundTrip() throws Exception {
		LockableResourcesManager.get().createResource("resource1");

		FreeStyleProject withResource = j.createFreeStyleProject("withResource");
		withResource.addProperty(new RequiredResourcesProperty("resource1", "resourceNameVar", null, null, null));
		FreeStyleProject withResourceRoundTrip = j.configRoundtrip(withResource);

		RequiredResourcesProperty withResourceProp = withResourceRoundTrip.getProperty(RequiredResourcesProperty.class);
		assertNotNull(withResourceProp);
		assertEquals("resource1", withResourceProp.getResourceNames());
		assertEquals("resourceNameVar", withResourceProp.getResourceNamesVar());
		assertEquals("", withResourceProp.getResourceNumber());
		assertEquals("", withResourceProp.getLabelName());
		assertNull(withResourceProp.getResourceMatchScript());

		FreeStyleProject withLabel = j.createFreeStyleProject("withLabel");
		withLabel.addProperty(new RequiredResourcesProperty(null, null, null, "some-label", null));
		FreeStyleProject withLabelRoundTrip = j.configRoundtrip(withLabel);

		RequiredResourcesProperty withLabelProp = withLabelRoundTrip.getProperty(RequiredResourcesProperty.class);
		assertNotNull(withLabelProp);
		assertEquals("", withLabelProp.getResourceNames());
		assertEquals("", withLabelProp.getResourceNamesVar());
		assertEquals("", withLabelProp.getResourceNumber());
		assertEquals("some-label", withLabelProp.getLabelName());
		assertNull(withLabelProp.getResourceMatchScript());

		FreeStyleProject withSpace = j.createFreeStyleProject("withSpace");
		withSpace.addProperty(new RequiredResourcesProperty(null, null, null, "some-label ", null));
		FreeStyleProject withSpaceRoundTrip = j.configRoundtrip(withSpace);

		RequiredResourcesProperty withSpaceProp = withSpaceRoundTrip.getProperty(RequiredResourcesProperty.class);
		assertNotNull(withSpaceProp);
		assertEquals("", withSpaceProp.getResourceNames());
		assertEquals("", withSpaceProp.getResourceNamesVar());
		assertEquals("", withSpaceProp.getResourceNumber());
		assertEquals("some-label", withSpaceProp.getLabelName());
		assertNull(withSpaceProp.getResourceMatchScript());

		FreeStyleProject withScript = j.createFreeStyleProject("withScript");
		SecureGroovyScript origScript = new SecureGroovyScript("return true", false, null);
		withScript.addProperty(new RequiredResourcesProperty(null, null, null, null, origScript));
		FreeStyleProject withScriptRoundTrip = j.configRoundtrip(withScript);

		RequiredResourcesProperty withScriptProp = withScriptRoundTrip.getProperty(RequiredResourcesProperty.class);
		assertNotNull(withScriptProp);
		assertEquals("", withScriptProp.getResourceNames());
		assertEquals("", withScriptProp.getResourceNamesVar());
		assertEquals("", withScriptProp.getResourceNumber());
		assertEquals("", withScriptProp.getLabelName());
		assertNotNull(withScriptProp.getResourceMatchScript());
		assertEquals("return true", withScriptProp.getResourceMatchScript().getScript());
		assertEquals(false, withScriptProp.getResourceMatchScript().isSandbox());
	}

	@Test
	public void validationFailure() throws Exception {
		RequiredResourcesProperty.DescriptorImpl d = new RequiredResourcesProperty.DescriptorImpl();
		LockableResourcesManager.get().createResource("resource1");
		LockableResource r = LockableResourcesManager.get().getResources().get(0);
		r.setLabels("some-label");

		assertEquals("Only label, groovy expression, or resources can be defined, not more than one.", d.doCheckResourceNames("resource1", null, true).getMessage());
		assertEquals("Only label, groovy expression, or resources can be defined, not more than one.", d.doCheckResourceNames("resource1", "some-label", false).getMessage());
		assertEquals("Only label, groovy expression, or resources can be defined, not more than one.", d.doCheckResourceNames("resource1", "some-label", true).getMessage());
		assertEquals("Only label, groovy expression, or resources can be defined, not more than one.", d.doCheckLabelName("some-label", "resource1", false).getMessage());
		assertEquals("Only label, groovy expression, or resources can be defined, not more than one.", d.doCheckLabelName("some-label", null, true).getMessage());
		assertEquals("Only label, groovy expression, or resources can be defined, not more than one.", d.doCheckLabelName("some-label", "resource1", true).getMessage());

		assertEquals(FormValidation.ok(), d.doCheckResourceNames("resource1", null, false));
		assertEquals(FormValidation.ok(), d.doCheckLabelName("some-label", null, false));
	}

	@TestExtension
	public static class PrinterBuilder extends MockBuilder {

		public PrinterBuilder() {
			super(Result.SUCCESS);
		}

		@Override
		public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
			listener.getLogger().println("resourceNameVar: " + build.getEnvironment(listener).get("resourceNameVar"));
			return true;
		}
		
	}

	@Test
	public void approvalRequired() throws Exception {
		LockableResourcesManager.get().createResource(LockableResourcesRootAction.ICON);

		j.jenkins.setSecurityRealm(j.createDummySecurityRealm());

		j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
				.grant(Jenkins.READ, Item.READ).everywhere().toAuthenticated()
				.grant(Jenkins.ADMINISTER).everywhere().to("bob")
				.grant(Item.CONFIGURE, Item.BUILD).everywhere().to("alice"));

		final String SCRIPT = "resourceName == org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction.ICON;";

		FreeStyleProject p = j.createFreeStyleProject();
		SecurityContext orig = ACL.impersonate(User.get("alice").impersonate());
		SecurityContextHolder.setContext(orig);
		SecureGroovyScript groovyScript = new SecureGroovyScript(SCRIPT, true, null).configuring(ApprovalContext.create());

		p.addProperty(new RequiredResourcesProperty(null, null, null, null, groovyScript));

		JenkinsRule.WebClient wc = j.createWebClient();
		wc.login("alice");

		QueueTaskFuture<FreeStyleBuild> futureBuild = p.scheduleBuild2(0);

		Queue.BlockedItem blockedItem = null;
		while (blockedItem == null) {
			List<Queue.Item> items = j.jenkins.getQueue().getItems(p);
			if (!items.isEmpty() && items.get(0) instanceof Queue.BlockedItem) {
				blockedItem = (Queue.BlockedItem) items.get(0);
			}
			if (blockedItem == null)
				Thread.sleep(1000);
		}
		assertTrue(blockedItem.getCauseOfBlockage() instanceof LockableResourcesQueueTaskDispatcher.BecauseResourcesQueueFailed);

		ScriptApproval approval = ScriptApproval.get();
		List<ScriptApproval.PendingSignature> pending = new ArrayList<>();
		pending.addAll(approval.getPendingSignatures());

		assertFalse(pending.isEmpty());
		assertEquals(1, pending.size());
		ScriptApproval.PendingSignature firstPending = pending.get(0);

		assertEquals("staticField org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction ICON",
				firstPending.signature);
		approval.approveSignature(firstPending.signature);

		j.assertBuildStatusSuccess(futureBuild);
	}


}
