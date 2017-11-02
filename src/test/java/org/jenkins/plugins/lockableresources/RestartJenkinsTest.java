package org.jenkins.plugins.lockableresources;

import hudson.Functions;
import hudson.Launcher;
import hudson.model.*;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.*;
import org.jvnet.hudson.test.recipes.WithPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;

import static java.lang.Thread.sleep;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeFalse;

public class RestartJenkinsTest {

	@Rule
	public RestartableJenkinsRule story = new RestartableJenkinsRule();

	@ClassRule
	public static BuildWatcher buildWatcher = new BuildWatcher();

	@Test
	public void lockOrderRestart() {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResource("resource1");
				WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition(
						"lock('resource1') {\n" +
						"	semaphore 'wait-inside'\n" +
						"}\n" +
						"echo 'Finish'"
				));
				WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
				SemaphoreStep.waitForStart("wait-inside/1", b1);
				WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
				// Ensure that b2 reaches the lock before b3
				story.j.waitForMessage("[resource1] is locked, waiting...", b2);
				WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
				// Both 2 and 3 are waiting for locking resource1

				story.j.waitForMessage("[resource1] is locked, waiting...", b3);
			}
		});

		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				WorkflowJob p = story.j.jenkins.getItemByFullName("p", WorkflowJob.class);
				WorkflowRun b1 = p.getBuildByNumber(1);
				WorkflowRun b2 = p.getBuildByNumber(2);
				WorkflowRun b3 = p.getBuildByNumber(3);

				// Unlock resource1
				SemaphoreStep.success("wait-inside/1", null);
				story.j.waitForMessage("Lock released on resource [resource1]", b1);

				story.j.waitForMessage("Lock acquired on [resource1]", b2);
				story.j.assertLogContains("[resource1] is locked, waiting...", b3);
				SemaphoreStep.success("wait-inside/2", null);
				SemaphoreStep.waitForStart("wait-inside/3", b3);
				story.j.assertLogContains("Lock acquired on [resource1]", b3);
				SemaphoreStep.success("wait-inside/3", null);
				story.j.waitForMessage("Finish", b3);
			}
		});
	}

	@Test
	public void interoperabilityOnRestart() {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResource("resource1");
				WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition(
						"lock('resource1') {\n" +
						"	semaphore 'wait-inside'\n" +
						"}\n" +
						"echo 'Finish'"
				));
				WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
				SemaphoreStep.waitForStart("wait-inside/1", b1);

				FreeStyleProject f = story.j.createFreeStyleProject("f");
				f.addProperty(new RequiredResourcesProperty("resource1", null, null, null));

				f.scheduleBuild2(0);

				while(story.j.jenkins.getQueue().getItems().length != 1) {
					System.out.println("Waiting for freestyle to be queued...");
					sleep(1000);
				}
			}
		});

		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				WorkflowJob p = story.j.jenkins.getItemByFullName("p", WorkflowJob.class);
				FreeStyleProject f = story.j.jenkins.getItemByFullName("f", FreeStyleProject.class);
				WorkflowRun b1;
				try {
					b1 = p.getBuildByNumber(1);
				} catch (AssertionError e) {
					// The builds lazyload and can race each other
					if (!e.getMessage().contains("tried to overwrite"))
						throw e;
					else
						b1 = p.getBuildByNumber(1);
				}

				// Unlock resource1
				SemaphoreStep.success("wait-inside/1", null);
				story.j.waitForMessage("Lock released on resource [resource1]", b1);
				FreeStyleBuild fb1 = null;
				while((fb1 = f.getBuildByNumber(1)) == null) {
					System.out.println("Waiting for freestyle #1 to start building...");
					sleep(1000);
				}

				story.j.waitForMessage("acquired lock on [resource1]", fb1);
			}
		});
	}

}
