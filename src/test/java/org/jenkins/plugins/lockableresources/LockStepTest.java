package org.jenkins.plugins.lockableresources;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;

import hudson.Functions;
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
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.recipes.WithPlugin;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeFalse;

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
	public void lockWithLabel() {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
				WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition(
						"lock(label: 'label1') {\n" +
						"	echo 'Resource locked'\n" +
						"}\n" +
						"echo 'Finish'"
				));
				WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
				story.j.waitForCompletion(b1);
				story.j.assertBuildStatus(Result.SUCCESS, b1);
				story.j.assertLogContains("Lock released on resource [Label: label1]", b1);
			}
		});
	}

	@Test
	public void lockOrderLabel() {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
				LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
				LockableResourcesManager.get().createResourceWithLabel("resource3", "label1");
				WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition(
						"lock(label: 'label1', quantity: 2) {\n" +
						"	semaphore 'wait-inside'\n" +
						"}\n" +
						"echo 'Finish'"
				));
				WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
				SemaphoreStep.waitForStart("wait-inside/1", b1);

				WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
				// Ensure that b2 reaches the lock before b3
				story.j.waitForMessage("[Label: label1, Quantity: 2] is locked, waiting...", b2);
				story.j.waitForMessage("Found 1 available resource(s). Waiting for correct amount: 2.", b2);
				WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
				// Both 2 and 3 are waiting for locking Label: label1, Quantity: 2
				story.j.waitForMessage("[Label: label1, Quantity: 2] is locked, waiting...", b3);
				story.j.waitForMessage("Found 1 available resource(s). Waiting for correct amount: 2.", b3);

				// Unlock Label: label1, Quantity: 2
				SemaphoreStep.success("wait-inside/1", null);
				story.j.waitForMessage("Lock released on resource [Label: label1, Quantity: 2]", b1);

				// #2 gets the lock before #3 (in the order as they requested the lock)
				story.j.waitForMessage("Lock acquired on [Label: label1, Quantity: 2]", b2);
				SemaphoreStep.success("wait-inside/2", null);
				story.j.waitForMessage("Finish", b2);
				story.j.waitForMessage("Lock acquired on [Label: label1, Quantity: 2]", b3);
				SemaphoreStep.success("wait-inside/3", null);
				story.j.waitForMessage("Finish", b3);
			}
		});
	}

	@Test
	public void lockOrderLabelQuantity() {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
				LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
				LockableResourcesManager.get().createResourceWithLabel("resource3", "label1");
				WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition(
						"lock(label: 'label1', quantity: 2) {\n" +
						"	semaphore 'wait-inside'\n" +
						"}\n" +
						"echo 'Finish'"
				));
				WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
				SemaphoreStep.waitForStart("wait-inside/1", b1);

				WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
				// Ensure that b2 reaches the lock before b3
				story.j.waitForMessage("[Label: label1, Quantity: 2] is locked, waiting...", b2);
				story.j.waitForMessage("Found 1 available resource(s). Waiting for correct amount: 2.", b2);

				WorkflowJob p3 = story.j.jenkins.createProject(WorkflowJob.class, "p3");
				p3.setDefinition(new CpsFlowDefinition(
						"lock(label: 'label1', quantity: 1) {\n" +
						"	semaphore 'wait-inside-quantity1'\n" +
						"}\n" +
						"echo 'Finish'"
				));
				WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
				// While 2 continues waiting, 3 can continue directly
				SemaphoreStep.waitForStart("wait-inside-quantity1/1", b3);
				// Let 3 finish
				SemaphoreStep.success("wait-inside-quantity1/1", null);
				story.j.waitForMessage("Finish", b3);

				// Unlock Label: label1, Quantity: 2
				SemaphoreStep.success("wait-inside/1", null);
				story.j.waitForMessage("Lock released on resource [Label: label1, Quantity: 2]", b1);

				// #2 gets the lock before #3 (in the order as they requested the lock)
				story.j.waitForMessage("Lock acquired on [Label: label1, Quantity: 2]", b2);
				SemaphoreStep.success("wait-inside/2", null);
				story.j.waitForMessage("Finish", b2);
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

				WorkflowJob p1 = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p1.setDefinition(new CpsFlowDefinition("lock('resource1') { echo 'locked!'; semaphore 'wait-inside' }"));
				WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
				story.j.waitForMessage("locked!", b1);
				SemaphoreStep.waitForStart("wait-inside/1", b1);

				WorkflowJob p2 = story.j.jenkins.createProject(WorkflowJob.class, "p2");
				p2.setDefinition(new CpsFlowDefinition(
						"lock('resource1') {\n"
						+ "  semaphore 'wait-inside'\n"
						+ "}"));
				WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();

				// Make sure that b2 is blocked on b1's lock.
				story.j.waitForMessage("[resource1] is locked, waiting...", b2);

				// Now b2 is still sitting waiting for a lock. Create b3 and launch it to clear the lock.
				WorkflowJob p3 = story.j.jenkins.createProject(WorkflowJob.class, "p3");
				p3.setDefinition(new CpsFlowDefinition(
						"lock('resource1') {\n"
								+ "  semaphore 'wait-inside'\n"
								+ "}"));
				WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
				story.j.waitForMessage("[resource1] is locked, waiting...", b3);

				// Kill b1 hard.
				b1.doKill();
				story.j.waitForMessage("Hard kill!", b1);
				story.j.waitForCompletion(b1);
				story.j.assertBuildStatus(Result.ABORTED, b1);


				// Verify that b2 gets the lock.
				story.j.waitForMessage("Lock acquired on [resource1]", b2);
				SemaphoreStep.success("wait-inside/2", b2);
				// Verify that b2 releases the lock and finishes successfully.
				story.j.waitForMessage("Lock released on resource [resource1]", b2);
				story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b2));

				// Now b3 should get the lock and do its thing.
				story.j.waitForMessage("Lock acquired on [resource1]", b3);
				SemaphoreStep.success("wait-inside/3", b3);
				story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b3));
			}
		});
	}

	// TODO: Figure out what to do about the IOException thrown during clean up, since we don't care about it. It's just
	// a result of the first build being deleted and is nothing but noise here.
	@Issue("JENKINS-36479")
	@Test public void deleteRunningBuildNewBuildClearsLock() throws Exception {
		assumeFalse(Functions.isWindows()); // TODO: Investigate failure on Windows.
		story.addStep(new Statement() {
			@Override public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResource("resource1");

				WorkflowJob p1 = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p1.setDefinition(new CpsFlowDefinition("lock('resource1') { echo 'locked!'; semaphore 'wait-inside' }"));
				WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
				story.j.waitForMessage("locked!", b1);
				SemaphoreStep.waitForStart("wait-inside/1", b1);

				WorkflowJob p2 = story.j.jenkins.createProject(WorkflowJob.class, "p2");
				p2.setDefinition(new CpsFlowDefinition(
						"lock('resource1') {\n"
								+ "  semaphore 'wait-inside'\n"
								+ "}"));
				WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();

				// Now b2 is still sitting waiting for a lock. Create b3 and launch it to clear the lock.
				WorkflowJob p3 = story.j.jenkins.createProject(WorkflowJob.class, "p3");
				p3.setDefinition(new CpsFlowDefinition(
						"lock('resource1') {\n"
								+ "  semaphore 'wait-inside'\n"
								+ "}"));
				WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();


				// Make sure that b2 is blocked on b1's lock.
				story.j.waitForMessage("[resource1] is locked, waiting...", b2);
				story.j.waitForMessage("[resource1] is locked, waiting...", b3);

				b1.delete();


				// Verify that b2 gets the lock.
				story.j.waitForMessage("Lock acquired on [resource1]", b2);
				SemaphoreStep.success("wait-inside/2", b2);
				// Verify that b2 releases the lock and finishes successfully.
				story.j.waitForMessage("Lock released on resource [resource1]", b2);
				story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b2));

				// Now b3 should get the lock and do its thing.
				story.j.waitForMessage("Lock acquired on [resource1]", b3);
				SemaphoreStep.success("wait-inside/3", b3);
				story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b3));
			}
		});
	}

	@Issue("JENKINS-40368")
	@Test
	public void hardKillWithWaitingRuns() throws Exception {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResource("resource1");
				WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition("retry(99) {\n" +
						"    lock('resource1') {\n" +
						"        semaphore('wait-inside')\n" +
						"     }\n" +
						"}", true));

				WorkflowRun prevBuild = null;
				for (int i = 0; i < 3; i++) {
					WorkflowRun rNext = p.scheduleBuild2(0).waitForStart();
					if (prevBuild != null) {
						story.j.waitForMessage("[resource1] is locked, waiting...", rNext);
						interruptTermKill(prevBuild);
					}

					story.j.waitForMessage("Lock acquired on [resource1]", rNext);

					SemaphoreStep.waitForStart("wait-inside/" + (i + 1), rNext);
					prevBuild = rNext;
				}
				SemaphoreStep.success("wait-inside/3", null);
				story.j.assertBuildStatus(Result.SUCCESS, story.j.waitForCompletion(prevBuild));
			}
		});
	}

	@Issue("JENKINS-40368")
	@Test
	public void hardKillWithWaitingRunsOnLabel() throws Exception {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
				LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
				WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition("retry(99) {\n" +
						"    lock(label: 'label1', quantity: 1) {\n" +
						"        semaphore('wait-inside')\n" +
						"     }\n" +
						"}", true));

				WorkflowRun firstPrev = null;
				WorkflowRun secondPrev = null;
				for (int i = 0; i < 3; i++) {
					WorkflowRun firstNext = p.scheduleBuild2(0).waitForStart();
					story.j.waitForMessage("Trying to acquire lock on", firstNext);
					WorkflowRun secondNext = p.scheduleBuild2(0).waitForStart();
					story.j.waitForMessage("Trying to acquire lock on", secondNext);

					if (firstPrev != null) {
						story.j.waitForMessage("is locked, waiting...", firstNext);
						story.j.waitForMessage("is locked, waiting...", secondNext);
					}

					interruptTermKill(firstPrev);
					story.j.waitForMessage("Lock acquired on ", firstNext);
					interruptTermKill(secondPrev);
					story.j.waitForMessage("Lock acquired on ", secondNext);

					SemaphoreStep.waitForStart("wait-inside/" + ((i * 2) + 1), firstNext);
					SemaphoreStep.waitForStart("wait-inside/" + ((i * 2) + 2), secondNext);
					firstPrev = firstNext;
					secondPrev = secondNext;
				}
				SemaphoreStep.success("wait-inside/5", null);
				SemaphoreStep.success("wait-inside/6", null);
				story.j.assertBuildStatus(Result.SUCCESS, story.j.waitForCompletion(firstPrev));
				story.j.assertBuildStatus(Result.SUCCESS, story.j.waitForCompletion(secondPrev));
			}
		});
	}

	private void interruptTermKill(WorkflowRun b) throws Exception {
		if (b != null) {
			Executor ex = b.getExecutor();
			assertNotNull(ex);
			ex.interrupt();
			story.j.waitForMessage("Click here to forcibly terminate running steps", b);
			b.doTerm();
			story.j.waitForMessage("Click here to forcibly kill entire build", b);
			b.doKill();
			story.j.waitForMessage("Hard kill!", b);
			story.j.waitForCompletion(b);
			story.j.assertBuildStatus(Result.ABORTED, b);
		}
	}

	@Test
	public void unlockButtonWithWaitingRuns() throws Exception {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResource("resource1");
				WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition("retry(99) {\n" +
						"    lock('resource1') {\n" +
						"        semaphore('wait-inside')\n" +
						"     }\n" +
						"}", true));

				JenkinsRule.WebClient wc = story.j.createWebClient();

				WorkflowRun prevBuild = null;
				for (int i = 0; i < 3; i++) {
					WorkflowRun rNext = p.scheduleBuild2(0).waitForStart();
					if (prevBuild != null) {
						story.j.waitForMessage("[resource1] is locked, waiting...", rNext);
						wc.goTo("lockable-resources/unlock?resource=resource1");
					}

					story.j.waitForMessage("Lock acquired on [resource1]", rNext);
					SemaphoreStep.waitForStart("wait-inside/" + (i + 1), rNext);

					if (prevBuild != null) {
						SemaphoreStep.success("wait-inside/" + i, null);
						story.j.assertBuildStatusSuccess(story.j.waitForCompletion(prevBuild));
					}
					prevBuild = rNext;
				}
				SemaphoreStep.success("wait-inside/3", null);
				story.j.assertBuildStatus(Result.SUCCESS, story.j.waitForCompletion(prevBuild));
			}
		});
	}

	@Issue("JENKINS-40879")
	@Test
	public void parallelLockRelease() throws Exception {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResource("resource1");
				LockableResourcesManager.get().createResource("resource2");
				WorkflowJob j = story.j.jenkins.createProject(WorkflowJob.class, "j");
				j.setDefinition(new CpsFlowDefinition(
						"lock(resource: 'resource1') {\n" +
								"    semaphore 'wait-inside-1'\n" +
								"}\n" +
								"lock(resource: 'resource2') { \n" +
								"    echo 'Entering semaphore now'\n" +
								"    semaphore 'wait-inside-2'\n" +
								"}\n",
						true));

				List<WorkflowRun> nextRuns = new ArrayList<>();

				WorkflowRun toUnlock = null;
				for (int i = 0; i < 5; i++) {
					WorkflowRun rNext = j.scheduleBuild2(0).waitForStart();
					if (toUnlock != null) {
						story.j.waitForMessage("[resource1] is locked, waiting...", rNext);
						SemaphoreStep.success("wait-inside-1/" + i, null);
					}
					SemaphoreStep.waitForStart("wait-inside-1/" + (i + 1), rNext);
					nextRuns.add(rNext);
					toUnlock = rNext;
				}
				SemaphoreStep.success("wait-inside-1/" + nextRuns.size(), null);
				waitAndClear(1, nextRuns);
			}
		});
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
				story.j.assertLogNotContains("Entering semaphore now", r);
			}
		}

		SemaphoreStep.success("wait-inside-2/" + semaphoreIndex, null);
		System.err.println("Waiting for " + toClear.getNumber() + " to complete");
		story.j.assertBuildStatusSuccess(story.j.waitForCompletion(toClear));

		if (!remainingRuns.isEmpty()) {
			waitAndClear(semaphoreIndex + 1, remainingRuns);
		}
	}

	@Test
	@WithPlugin("jobConfigHistory.hpi")
	public void lockWithLabelConcurrent() {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
				final WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition(
						"import java.util.Random; \n" +
								"Random random = new Random(0);\n" +
								"lock(label: 'label1') {\n" +
								"  echo 'Resource locked'\n" +
								"  sleep random.nextInt(10)*100\n" +
								"}\n" +
								"echo 'Finish'"
				));
				final CyclicBarrier barrier = new CyclicBarrier(51);
				for (int i = 0; i < 50; i++) {
					Thread thread = new Thread() {
						public void run() {
							try {
								barrier.await();
								WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
							} catch (Exception e) {
								System.err.println("Failed to start pipeline job");
							}
						}
					};
					thread.start();
				}
				barrier.await();
				story.j.waitUntilNoActivity();
			}
		});
	}
}
