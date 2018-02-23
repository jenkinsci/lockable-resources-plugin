package org.jenkins.plugins.lockableresources;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.util.Arrays;
import java.util.concurrent.Semaphore;

@RunWith(Parameterized.class)
public class FreeStyleOrderingTest {

	@Rule
	public RestartableJenkinsRule story = new RestartableJenkinsRule();

	@ClassRule
	public static BuildWatcher buildWatcher = new BuildWatcher();

	@Parameterized.Parameters(name = "{index}: fib({0})={1}")
	public static Iterable<Object[]> data() {
		return Arrays.asList(new Object[][] {
				{ UtilFn.labelResource("label1"), "resource1" },
				{ UtilFn.labelResource("label1", 2), "Label: label1, Quantity: 2" }
		});
	}
	private RequiredResourcesProperty requestedResource;
	private String lockMessage;

	public FreeStyleOrderingTest(RequiredResourcesProperty requestedResource, String lockMessage) {
		this.requestedResource = requestedResource;
		this.lockMessage = lockMessage;
	}


	@Test
	public void lockOrderLabelFreestyle() {
		final Semaphore semaphore = new Semaphore(1);
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
				LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
				LockableResourcesManager.get().createResourceWithLabel("resource3", "label1");
				int stepIndex = 1;
				FreeStyleProject p = UtilFn.createWaitingForResourcesFreestyleJob(story, UtilFn.labelResource("label1", 2), "p");
				FreeStyleBuild b1 = p.scheduleBuild2(0).waitForStart();
				FreeStyleBuild b2 = p.scheduleBuild2(0).waitForStart();
				FreeStyleBuild b3 = p.scheduleBuild2(0).waitForStart();
				story.j.waitForMessage("Finish", b1);
				story.j.assertBuildStatus(Result.SUCCESS, b1);
				story.j.waitForMessage("Finish", b2);
				story.j.assertBuildStatus(Result.SUCCESS, b2);
				story.j.waitForMessage("Finish", b3);
				story.j.assertBuildStatus(Result.SUCCESS, b3);
				}
		});
	}

}
