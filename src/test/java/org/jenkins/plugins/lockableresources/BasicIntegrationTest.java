package org.jenkins.plugins.lockableresources;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import hudson.model.ItemGroup;
import hudson.model.queue.QueueTaskFuture;
import hudson.triggers.TimerTrigger;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
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

		FreeStyleProject p2 = j.jenkins.getItem("p", (ItemGroup)null, FreeStyleProject.class);
		RequiredResourcesProperty newProp = p2.getProperty(RequiredResourcesProperty.class);
		assertNull(newProp.getLabelName());
		assertNotNull(newProp.getScript());
		assertEquals("resourceName == 'resource1'", newProp.getScript().getScript());

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
		assertNull(withResourceProp.getScript());

		FreeStyleProject withLabel = j.createFreeStyleProject("withLabel");
		withLabel.addProperty(new RequiredResourcesProperty(null, null, null, "some-label", null));
		FreeStyleProject withLabelRoundTrip = j.configRoundtrip(withLabel);

		RequiredResourcesProperty withLabelProp = withLabelRoundTrip.getProperty(RequiredResourcesProperty.class);
		assertNotNull(withLabelProp);
		assertEquals("", withLabelProp.getResourceNames());
		assertEquals("", withLabelProp.getResourceNamesVar());
		assertEquals("", withLabelProp.getResourceNumber());
		assertEquals("some-label", withLabelProp.getLabelName());
		assertNull(withLabelProp.getScript());

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
		assertNotNull(withScriptProp.getScript());
		assertEquals("return true", withScriptProp.getScript().getScript());
		assertEquals(false, withScriptProp.getScript().isSandbox());
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

}
