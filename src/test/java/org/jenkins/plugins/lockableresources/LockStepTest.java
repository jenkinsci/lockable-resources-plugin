package org.jenkins.plugins.lockableresources;

import java.io.IOException;
import java.util.concurrent.Semaphore;

import hudson.model.Executor;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.RestartableJenkinsRule;
import org.jvnet.hudson.test.TestBuilder;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class LockStepTest {

	@Rule
	public RestartableJenkinsRule story = new RestartableJenkinsRule();

	@ClassRule
	public static BuildWatcher buildWatcher = new BuildWatcher();

	@Test
	public void autoCreateResource() {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition(
						"lock('resource1') {\n" +
						"	echo 'Resource locked'\n" +
						"}\n" +
						"echo 'Finish'"
				));
				WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
				story.j.waitForCompletion(b1);
				story.j.assertBuildStatus(Result.SUCCESS, b1);
				story.j.assertLogContains("Resource [resource1] did not exist. Created.", b1);
			}
		});
	}

	@Test
	public void lockOrder() {
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

				// Unlock resource1
				SemaphoreStep.success("wait-inside/1", null);
				story.j.waitForMessage("Lock released on resource [resource1]", b1);

				// #2 gets the lock before #3 (in the order as they requested the lock)
				story.j.waitForMessage("Lock acquired on [resource1]", b2);
				SemaphoreStep.success("wait-inside/2", null);
				story.j.waitForMessage("Lock acquired on [resource1]", b3);
				SemaphoreStep.success("wait-inside/3", null);
				story.j.waitForMessage("Finish", b3);
			}
		});
	}

	@Test
	public void lockInverseOrder() {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResource("resource1");
				WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition(
						"lock(resource: 'resource1', inversePrecedence: true) {\n" +
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

				// Unlock resource1
				SemaphoreStep.success("wait-inside/1", null);
				story.j.waitForMessage("Lock released on resource [resource1]", b1);

				// #3 gets the lock before #2 because of inversePrecedence
				story.j.waitForMessage("Lock acquired on [resource1]", b3);
				SemaphoreStep.success("wait-inside/2", null);
				story.j.waitForMessage("Lock acquired on [resource1]", b2);
				SemaphoreStep.success("wait-inside/3", null);
				story.j.waitForMessage("Finish", b3);
			}
		});
	}

	@Test
	public void parallelLock() {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResource("resource1");
				WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition(
						"parallel a: {\n" +
						"	sleep 5\n" +
						"	lock('resource1') {\n" +
						"		sleep 5\n" +
						"	}\n" +
						"}, b: {\n" +
						"	lock('resource1') {\n" +
						"		semaphore 'wait-b'\n" +
						"	}\n" +
						"}\n"
				));

				WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
				SemaphoreStep.waitForStart("wait-b/1", b1);
				// both messages are in the log because branch b acquired the lock and branch a is waiting to lock
				story.j.waitForMessage("[b] Lock acquired on [resource1]", b1);
				story.j.waitForMessage("[a] [resource1] is locked, waiting...", b1);

				SemaphoreStep.success("wait-b/1", null);

				story.j.waitForMessage("[a] Lock acquired on [resource1]", b1);
			}
		});
	}

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
	public void interoperability() {
		final Semaphore semaphore = new Semaphore(1);
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResource("resource1");
				WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition(
						"lock('resource1') {\n" +
						"	echo 'Locked'\n" +
						"}\n" +
						"echo 'Finish'"
				));

				FreeStyleProject f = story.j.createFreeStyleProject("f");
				f.addProperty(new RequiredResourcesProperty("resource1", null, null, null));
				f.getBuildersList().add(new TestBuilder() {

					@Override
					public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
						semaphore.acquire();
						return true;
					}

				});
				semaphore.acquire();
				f.scheduleBuild2(0).waitForStart();

				WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
				story.j.waitForMessage("[resource1] is locked, waiting...", b1);
				semaphore.release();

				// Wait for lock after the freestyle finishes
				story.j.waitForMessage("Lock released on resource [resource1]", b1);
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
					Thread.sleep(1000);
				}
			}
		});

		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				WorkflowJob p = story.j.jenkins.getItemByFullName("p", WorkflowJob.class);
				FreeStyleProject f = story.j.jenkins.getItemByFullName("f", FreeStyleProject.class);
				WorkflowRun b1 = p.getBuildByNumber(1);


				// Unlock resource1
				SemaphoreStep.success("wait-inside/1", null);
				story.j.waitForMessage("Lock released on resource [resource1]", b1);
				FreeStyleBuild fb1 = null;
				while((fb1 = f.getBuildByNumber(1)) == null) {
					System.out.println("Waiting for freestyle #1 to start building...");
					Thread.sleep(1000);
				}

				story.j.waitForMessage("acquired lock on [resource1]", fb1);
			}
		});
	}

	@Issue("JENKINS-36479")
	@Test public void hardKillNewBuildClearsLock() throws Exception {
		story.addStep(new Statement() {
			@Override public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResource("resource1");

				WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition("lock('resource1') { echo 'locked!'; semaphore 'wait-inside' }"));
				WorkflowRun b = p.scheduleBuild2(0).waitForStart();
				story.j.waitForMessage("locked!", b);
				SemaphoreStep.waitForStart("wait-inside/1", b);
				Executor ex = b.getExecutor();
				assertNotNull(ex);
				ex.interrupt();
				b.doTerm();
				ex.interrupt();
				b.doKill();
				story.j.waitForMessage("Hard kill!", b);
				story.j.waitForCompletion(b);
				story.j.assertBuildStatus(Result.ABORTED, b);

				// TODO: Actually make this fail instead of waiting forever if it can't get the lock.
				WorkflowJob p2 = story.j.jenkins.createProject(WorkflowJob.class, "p2");
				p2.setDefinition(new CpsFlowDefinition("lock('resource1') { echo 'got lock' }"));
				WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
				story.j.waitForMessage("Lock released on resource [resource1]", b2);
				story.j.waitForMessage("got lock", b2);
				story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b2));
			}
		});
	}

}
