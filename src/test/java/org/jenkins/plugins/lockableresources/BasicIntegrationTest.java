package org.jenkins.plugins.lockableresources;

import java.io.IOException;
import java.util.Collections;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockBuilder;
import org.jvnet.hudson.test.TestExtension;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;

public class BasicIntegrationTest {

	@Rule
	public JenkinsRule jenkinsRule = new JenkinsRule();

	@Test
	@Issue("JENKINS-34853")
	public void security170fix() throws Exception {
		LockableResourcesManager.get().createResource("resource1");

		FreeStyleProject project = jenkinsRule.createFreeStyleProject("project");

		project.addProperty(new RequiredResourcesProperty(Collections.singletonList(new RequiredResources("resource1", "resourceNameVar", null, null))));
		project.getBuildersList().add(new PrinterBuilder());

		FreeStyleBuild build = project.scheduleBuild2(0).get();

		jenkinsRule.assertLogContains("resourceNameVar: resource1", build);
		jenkinsRule.assertBuildStatus(Result.SUCCESS, build);
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
