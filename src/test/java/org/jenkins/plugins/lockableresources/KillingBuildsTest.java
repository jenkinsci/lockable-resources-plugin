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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeFalse;

public class KillingBuildsTest {

	@Rule
	public RestartableJenkinsRule story = new RestartableJenkinsRule();

	@ClassRule
	public static BuildWatcher buildWatcher = new BuildWatcher();

	@Issue("JENKINS-36479")
	@Test public void hardKillNewBuildClearsLock() throws Exception {
		story.addStep(new Statement() {
			@Override public void evaluate() throws Throwable {
				int stepIndex = 1;
				String lockMessage = "resource1";
				LockableResourcesManager.get().createResource(lockMessage);
				WorkflowJob p1 = UtilFn.createWaitingForResourcesJob(story, "'" + lockMessage + "'", "p");
				WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
				story.j.waitForMessage("Lock acquired", b1);
				SemaphoreStep.waitForStart("wait-inside/" + stepIndex++, b1);

				WorkflowRun b2 = UtilFn.scheduleBuildAndCheckWaiting(story, p1, lockMessage, 1, 0);
				WorkflowRun b3 = UtilFn.scheduleBuildAndCheckWaiting(story, p1, lockMessage, 1, 0);

				// Kill b1 hard.
				b1.doKill();
				story.j.waitForMessage("Hard kill!", b1);
				story.j.waitForCompletion(b1);
				story.j.assertBuildStatus(Result.ABORTED, b1);

				// Verify that b2 gets the lock.
				// Verify that b2 releases the lock and finishes successfully.
				stepIndex = UtilFn.acquireLockAndFinishBuild(story, b2, lockMessage, stepIndex);
				// Now b3 should get the lock and do its thing.
				stepIndex = UtilFn.acquireLockAndFinishBuild(story, b3, lockMessage, stepIndex);
			}
		});
	}

	@Test public void hardKillQueuedBuildDoesntTakeLock() throws Exception {
		story.addStep(new Statement() {
			@Override public void evaluate() throws Throwable {
				int stepIndex = 1;
				String lockMessage = "resource1";
				LockableResourcesManager.get().createResource(lockMessage);
				WorkflowJob p1 = UtilFn.createWaitingForResourcesJob(story, "'" + lockMessage + "'", "p");
				WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
				story.j.waitForMessage("Lock acquired", b1);
				SemaphoreStep.waitForStart("wait-inside/" + stepIndex, b1);

				WorkflowRun b2 = UtilFn.scheduleBuildAndCheckWaiting(story, p1, lockMessage, 1, 0);
				WorkflowRun b3 = UtilFn.scheduleBuildAndCheckWaiting(story, p1, lockMessage, 1, 0);

				// Kill b2 hard while it's in the queue.
				b2.doKill();
				story.j.waitForMessage("Hard kill!", b2);
				story.j.waitForCompletion(b2);
				story.j.assertBuildStatus(Result.ABORTED, b2);

				// Verify that b1 releases the lock and finishes successfully.
				stepIndex = UtilFn.acquireLockAndFinishBuild(story, b1, lockMessage, stepIndex);
				// Now b3 should get the lock and do its thing.
				stepIndex = UtilFn.acquireLockAndFinishBuild(story, b3, lockMessage, stepIndex);
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
				int stepIndex = 1;
				String lockMessage = "resource1";
				LockableResourcesManager.get().createResource(lockMessage);
				WorkflowJob p1 = UtilFn.createWaitingForResourcesJob(story, "'" + lockMessage + "'", "p");
				WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
				story.j.waitForMessage("Lock acquired", b1);
				SemaphoreStep.waitForStart("wait-inside/" + stepIndex++, b1);

				WorkflowRun b2 = UtilFn.scheduleBuildAndCheckWaiting(story, p1, lockMessage, 1, 0);
				WorkflowRun b3 = UtilFn.scheduleBuildAndCheckWaiting(story, p1, lockMessage, 1, 0);

				b1.delete();

				// Verify that b2 gets the lock.
				// Verify that b2 releases the lock and finishes successfully.
				stepIndex = UtilFn.acquireLockAndFinishBuild(story, b2, lockMessage, stepIndex);
				// Now b3 should get the lock and do its thing.
				stepIndex = UtilFn.acquireLockAndFinishBuild(story, b3, lockMessage, stepIndex);

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
}
