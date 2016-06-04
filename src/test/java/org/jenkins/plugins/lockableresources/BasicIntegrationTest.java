package org.jenkins.plugins.lockableresources;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;

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
