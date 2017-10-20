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

	// Below are utility functions to help with some of the repetitive setup and running of the tests
	private WorkflowJob createWaitingForResourcesJob(String lockParameters, String jobName) throws Throwable {
		// Create job definition that tries to acquire a lock and then wait on a semaphore
		WorkflowJob resourceJob = story.j.jenkins.createProject(WorkflowJob.class, jobName);
		resourceJob.setDefinition(new CpsFlowDefinition(
				"lock(" + lockParameters + ") {\n" +
						"	semaphore 'wait-inside'\n" +
						"}\n" +
						"echo 'Finish'"
		));
		return resourceJob;
	}

	private WorkflowRun scheduleBuildAndCheckWaiting(WorkflowJob job, String lockMessage, int correctAmount, int available) throws Throwable {
		// schedule a build for which it is expected there aren't enough resources to start and the build will wait
		WorkflowRun build = job.scheduleBuild2(0).waitForStart();
		story.j.waitForMessage("Trying to acquire lock on [" + lockMessage + "]", build);
		story.j.waitForMessage("Found " + available + " available resource(s). Waiting for correct amount: " + correctAmount + ".", build);
		return build;
	}

	private int acquireLockAndFinishBuild(WorkflowRun build, String lockMessage, int stepIndex) throws Throwable{
		// the build should be able to acquire the lock it needs, signal its semaphore and finish successfully
		story.j.waitForMessage("Lock acquired on [" + lockMessage + "]", build);
		SemaphoreStep.success("wait-inside/" + stepIndex++, null);
		story.j.waitForMessage("Lock released on resource [" + lockMessage + "]", build);
		story.j.waitForMessage("Finish", build);
		return stepIndex;
	}

	@Test
	public void mixOfLabelLockThenSpecificResource() {
		// Test that when mixing requests for locks based on labels and then specific resources are handled
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				int stepIndex = 1;
				LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
				LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
				WorkflowJob labelJob = createWaitingForResourcesJob("label: 'label1', quantity: 2", "labelJob");
				String labelLockMessage = "Label: label1, Quantity: 2";
				WorkflowJob resource1Job = createWaitingForResourcesJob("resource: 'resource1'", "resource1Job");
				String resource1LockMessage = "resource1";
				WorkflowJob resource2Job = createWaitingForResourcesJob("resource: 'resource2'", "resource2Job");
				String resource2LockMessage = "resource2";
				// Label build runs first and acquires resource 1 and 2
				WorkflowRun labelBuild1 = labelJob.scheduleBuild2(0).waitForStart();
				SemaphoreStep.waitForStart("wait-inside/1", labelBuild1);

				// Specific resource lock build will both wait on the label build
				WorkflowRun resource1Build = scheduleBuildAndCheckWaiting(resource1Job, resource1LockMessage, 1, 0);
				WorkflowRun resource2Build = scheduleBuildAndCheckWaiting(resource2Job, resource2LockMessage, 1, 0);

				stepIndex = acquireLockAndFinishBuild(labelBuild1, labelLockMessage, stepIndex);
				// Once the label build has finished, the 2 specific lock builds should complete
				stepIndex = acquireLockAndFinishBuild(resource1Build, resource1LockMessage, stepIndex);
				stepIndex = acquireLockAndFinishBuild(resource2Build, resource2LockMessage, stepIndex);
			}
		});
	}

	@Test
	public void mixOfSpecificResourceThenLabelLocks() {
		// Test that when mixing requests for locks based on specific resources and then label resources are handled
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				int stepIndex = 1;
				LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
				LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
				WorkflowJob labelJob = createWaitingForResourcesJob("label: 'label1', quantity: 2", "label");
				String labelLockMessage = "Label: label1, Quantity: 2";
				WorkflowJob resource1Job = createWaitingForResourcesJob("resource: 'resource1'", "resource1");
				String resource1LockMessage = "resource1";
				WorkflowJob resource2Job = createWaitingForResourcesJob("resource: 'resource2'", "resource2");
				String resource2LockMessage = "resource2";
				WorkflowRun resource1Build = resource1Job.scheduleBuild2(0).waitForStart();
				SemaphoreStep.waitForStart("wait-inside/1", resource1Build);

				// labelBuild will wait as resource1 has been locked and it needs both resource[12]
				WorkflowRun labelBuild = scheduleBuildAndCheckWaiting(labelJob, labelLockMessage, 2, 1);

				// resource2Build is free to schedule and acquire lock as labelBuild hasn't locked resource2
				WorkflowRun resource2Build = resource2Job.scheduleBuild2(0).waitForStart();
				SemaphoreStep.waitForStart("wait-inside/2", resource2Build);

				// resource[12]Builds should complete and release their locks
				stepIndex = acquireLockAndFinishBuild(resource1Build, resource1LockMessage, stepIndex);
				stepIndex = acquireLockAndFinishBuild(resource2Build, resource2LockMessage, stepIndex);

				// resource[12] are now free, so label build is free to finish
				stepIndex = acquireLockAndFinishBuild(labelBuild, labelLockMessage, stepIndex);
			}
		});
	}

	@Test
	public void resourcesWithLabelsAreAllAcquirable() {
		// Test making multiple requests on a label that ultimately use all the available resources
		// should take all those resources and not block
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
				LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
				LockableResourcesManager.get().createResourceWithLabel("resource3", "label1");
				LockableResourcesManager.get().createResourceWithLabel("resource4", "label1");
				LockableResourcesManager.get().createResourceWithLabel("resource5", "label1");
				LockableResourcesManager.get().createResourceWithLabel("resource6", "label1");
				WorkflowJob labelJob = createWaitingForResourcesJob("label: 'label1', quantity: 2", "label");
				String labelLockMessage = "Label: label1, Quantity: 2";

				// Make sure all builds schedule and acquire all locks. No queueing.
				// The actual resources selected should be random, but they should all be acquired.
				WorkflowRun labelBuild1 = labelJob.scheduleBuild2(0).waitForStart();
				SemaphoreStep.waitForStart("wait-inside/1", labelBuild1);
				story.j.waitForMessage("Lock acquired on [" + labelLockMessage + "]", labelBuild1);
				WorkflowRun labelBuild2 = labelJob.scheduleBuild2(0).waitForStart();
				SemaphoreStep.waitForStart("wait-inside/2", labelBuild2);
				story.j.waitForMessage("Lock acquired on [" + labelLockMessage + "]", labelBuild2);
				WorkflowRun labelBuild3 = labelJob.scheduleBuild2(0).waitForStart();
				SemaphoreStep.waitForStart("wait-inside/3", labelBuild3);
				story.j.waitForMessage("Lock acquired on [" + labelLockMessage + "]", labelBuild3);

				// ok to complete the builds in reverse order as nothing is waiting on locks
				int stepIndex = 3;
				SemaphoreStep.success("wait-inside/" + stepIndex--, null);
				story.j.waitForMessage("Finish", labelBuild3);
				SemaphoreStep.success("wait-inside/" + stepIndex--, null);
				story.j.waitForMessage("Finish", labelBuild2);
				SemaphoreStep.success("wait-inside/" + stepIndex--, null);
				story.j.waitForMessage("Finish", labelBuild1);
			}
		});
	}

	@Test
	public void jobOrderingPreservedWithinPriorityBands() {
		// Testing that high priority builds are scheduled in advance of lower priority builds, but within a
		// priority band, builds still acquire the lock in the order they were scheduled
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				int stepIndex = 1;
				LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
				WorkflowJob resource1Job = createWaitingForResourcesJob("resource: 'resource1'", "no-priority");
				String resource1LockMessage = "resource1";
				WorkflowJob resource1P10Job = createWaitingForResourcesJob("resource: 'resource1', lockPriority: 10", "priority10");
				String resource1P10LockMessage = "resource1, LockPriority: 10";
				WorkflowJob resource1P20Job = createWaitingForResourcesJob("resource: 'resource1', lockPriority: 20", "priority20");
				String resource1P20LockMessage = "resource1, LockPriority: 20";

				// First build to be scheduled takes lock, even though low priority
				WorkflowRun resource1Build1 = resource1Job.scheduleBuild2(0).waitForStart();
				SemaphoreStep.waitForStart("wait-inside/1", resource1Build1);

				// schedule 2 builds each for priorities 20, 10 and no priority, with priority order mixed up
				WorkflowRun resource1Build2 = scheduleBuildAndCheckWaiting(resource1Job, resource1LockMessage, 1, 0);
				WorkflowRun resource1Build3 = scheduleBuildAndCheckWaiting(resource1Job, resource1LockMessage, 1, 0);
				WorkflowRun resource1P10Build1 = scheduleBuildAndCheckWaiting(resource1P10Job, resource1P10LockMessage, 1, 0);
				WorkflowRun resource1P20Build1 = scheduleBuildAndCheckWaiting(resource1P20Job, resource1P20LockMessage, 1, 0);
				WorkflowRun resource1P20Build2 = scheduleBuildAndCheckWaiting(resource1P20Job, resource1P20LockMessage, 1, 0);
				WorkflowRun resource1P10Build2 = scheduleBuildAndCheckWaiting(resource1P10Job, resource1P10LockMessage, 1, 0);

				// Finish the first build so the subsequent builds can complete
				stepIndex = acquireLockAndFinishBuild(resource1Build1, resource1LockMessage, stepIndex);

				// make sure the P20 builds complete first, then the P10, then the no-priority builds
				// The order in the priority bands is preserved though
				stepIndex = acquireLockAndFinishBuild(resource1P20Build1, resource1P20LockMessage, stepIndex);
				stepIndex = acquireLockAndFinishBuild(resource1P20Build2, resource1P20LockMessage, stepIndex);

				stepIndex = acquireLockAndFinishBuild(resource1P10Build1, resource1P10LockMessage, stepIndex);
				stepIndex = acquireLockAndFinishBuild(resource1P10Build2, resource1P10LockMessage, stepIndex);

				stepIndex = acquireLockAndFinishBuild(resource1Build2, resource1LockMessage, stepIndex);
				stepIndex = acquireLockAndFinishBuild(resource1Build3, resource1LockMessage, stepIndex);
			}
		});
	}

	@Test
	public void lockOrderLabelWithPriorityResource() {
		// Testing that mixing label based locks with specific resources locks and priorities work
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				int stepIndex = 1;
				LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
				LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
				WorkflowJob labelJob = createWaitingForResourcesJob("label: 'label1', quantity: 2", "labelPriority0");
				String labelLockMessage = "Label: label1, Quantity: 2";
				WorkflowJob resource1Job = createWaitingForResourcesJob("resource: 'resource1', lockPriority: 10", "priority10");
				String resource1LockMessage = "resource1, LockPriority: 10";
				WorkflowJob resource2Job = createWaitingForResourcesJob("resource: 'resource2', lockPriority: 20", "priority20");
				String resource2LockMessage = "resource2, LockPriority: 20";
				WorkflowRun labelBuild1 = labelJob.scheduleBuild2(0).waitForStart();
				SemaphoreStep.waitForStart("wait-inside/1", labelBuild1);

				WorkflowRun labelBuild2 = scheduleBuildAndCheckWaiting(labelJob, labelLockMessage, 2, 0);
				WorkflowRun labelBuild3 = scheduleBuildAndCheckWaiting(labelJob, labelLockMessage, 2, 0);
				// Both 2 and 3 are waiting for locking Label: label1, Quantity: 2

				// These 2 builds waiting for resource1 have a priority of 10, so will jump the queue for labelBuild2 and labelBuild3 above
				WorkflowRun resource1Build1 = scheduleBuildAndCheckWaiting(resource1Job, resource1LockMessage, 1, 0);
				WorkflowRun resource1Build2 = scheduleBuildAndCheckWaiting(resource1Job, resource1LockMessage, 1, 0);

				// This build for resource2 has priority 20, though scheduled last, will run first once its resource is freed
				WorkflowRun resource2Build = scheduleBuildAndCheckWaiting(resource2Job, resource2LockMessage, 1, 0);

				// All other builds are now waiting for resources that labelBuild1 has locked
				stepIndex = acquireLockAndFinishBuild(labelBuild1, labelLockMessage, stepIndex);

				// resource2Build had the highest priority, so proceeds first
				stepIndex = acquireLockAndFinishBuild(resource2Build, resource2LockMessage, stepIndex);

				// both #resource1Build[12] had a high priority (10) so get the lock before labelBuild2 and labelBuild3,
				// but their order is still preserved
				stepIndex = acquireLockAndFinishBuild(resource1Build1, resource1LockMessage, stepIndex);
				stepIndex = acquireLockAndFinishBuild(resource1Build2, resource1LockMessage, stepIndex);

				// labelBuild2 and labelBuild3 were priority 0 and so get the locks last.
				// #2 gets the lock before #3 (in the order as they requested the lock)
				stepIndex = acquireLockAndFinishBuild(labelBuild2, labelLockMessage, stepIndex);
				stepIndex = acquireLockAndFinishBuild(labelBuild3, labelLockMessage, stepIndex);
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

	@Test
	public void lockWithVariable() {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				LockableResourcesManager.get().createResource("resource0");
                LockableResourcesManager.get().createResourceWithLabel("resource1", "res");
                LockableResourcesManager.get().createResourceWithLabel("resource2", "res");
                LockableResourcesManager.get().createResourceWithLabel("resource3", "res");
				WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition(
						"lock(resource: 'resource0', variable: 'MY_VAR') {\n" +
								"	echo 'Resource locked'\n" +
								"	echo \"got resource:[ ${env.MY_VAR} ]\"\n" +
								"}\n" +
                                "lock(label: 'res', variable: 'MY_VAR') {\n" +
                                "	echo 'multiple resources locked'\n" +
                                "	echo \"got resources:[ ${env.MY_VAR} ]\"\n" +
                                "}\n" +
                                "echo 'Finish'"
				));
				WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
				story.j.waitForCompletion(b1);
				story.j.assertBuildStatus(Result.SUCCESS, b1);
				story.j.assertLogContains("Lock released on resource [resource0, Variable: MY_VAR]", b1);
				story.j.assertLogContains("got resource:[ resource0 ]", b1);

                story.j.assertLogContains("Lock released on resource [Label: res, Variable: MY_VAR]", b1);
                story.j.assertLogContains("got resources:[ resource1, resource2, resource3 ]", b1);
			}
		});
	}

    @Test
    public void lockWithVariableNested() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResource("resource1");
                LockableResourcesManager.get().createResource("resource2");
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "lock(resource: 'resource1', variable: 'MY_VAR1') {\n" +
                                "	echo \"got resource#1.1:[ ${env.MY_VAR1} ]\"\n" +
                                "   lock(resource: 'resource2', variable: 'MY_VAR2') {\n" +
								"	   echo \"got resource#1.2:[ ${env.MY_VAR1} ]\"\n" +
                                "	   echo \"got resource#2.1:[ ${env.MY_VAR2} ]\"\n" +
                                "   }\n" +
                                "	echo \"got resource#1.3:[ ${env.MY_VAR1} ]\"\n" +
                                "	echo \"(unset after block) got no resource#:[ ${env.MY_VAR2} ]\"\n" +
                                "   lock(resource: 'resource2', variable: 'MY_VAR1') {\n" +
                                "	   echo \"(shadowing) got resource#2.2:[ ${env.MY_VAR1} ]\"\n" +
                                "   }\n" +
                                "	echo \"(is reset) got resource#1.4:[ ${env.MY_VAR1} ]\"\n" +
                                "}\n" +
                                "echo 'Finish'"
                ));
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                story.j.waitForCompletion(b1);
                story.j.assertBuildStatus(Result.SUCCESS, b1);
                story.j.assertLogContains("got resource#1.1:[ resource1 ]", b1);

                story.j.assertLogContains("got resource#1.2:[ resource1 ]", b1);
                story.j.assertLogContains("got resource#2.1:[ resource2 ]", b1);

                story.j.assertLogContains("got resource#1.3:[ resource1 ]", b1);
                // "null" is a bit strange but more of an echo problem
                story.j.assertLogContains("got no resource#:[ null ]", b1);
                story.j.assertLogContains("got resource#2.2:[ resource2 ]", b1);
                story.j.assertLogContains("got resource#1.4:[ resource1 ]", b1);
            }
        });
    }

    @Test
    public void lockWithVariableParallel() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResource("resource1");
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "parallel a: {\n" +
                                "	sleep 5\n" +
                                "	lock(resource: 'resource1', variable: 'MY_VAR') {\n" +
                                "	    echo \"got resource#1:[ ${env.MY_VAR} ]\"\n" +
                                "	}\n" +
                                "}, b: {\n" +
                                "	lock(resource: 'resource1', variable: 'MY_VAR') {\n" +
                                "	   echo \"got resource#2:[ ${env.MY_VAR} ]\"\n" +
                                "	   sleep 7\n" +
                                "	}\n" +
                                "}\n"
                ));

                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                story.j.waitForCompletion(b1);

                story.j.assertLogContains("[b] Lock acquired on [resource1, Variable: MY_VAR]", b1);
                story.j.assertLogContains("[b] got resource#2:[ resource1 ]", b1);
                story.j.assertLogContains("[a] [resource1, Variable: MY_VAR] is locked, waiting...", b1);

                story.j.assertLogContains("[a] Lock acquired on [resource1, Variable: MY_VAR]", b1);
                story.j.assertLogContains("[a] got resource#1:[ resource1 ]", b1);
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
