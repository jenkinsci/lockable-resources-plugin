package org.jenkins.plugins.lockableresources;


import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.RestartableJenkinsRule;

public class LockStepTest {

	@Rule
	public RestartableJenkinsRule story = new RestartableJenkinsRule();

	@ClassRule
	public static BuildWatcher buildWatcher = new BuildWatcher();

	@Test
	public void lockOrder() {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				defineResource("resource1");
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
				story.j.waitForMessage("[resource1] is locked by p#1", b2);
				WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
				// Both 2 and 3 are waiting for locking resource1

				story.j.waitForMessage("[resource1] is locked by p#1", b3);

				// Unlock resource1
				SemaphoreStep.success("wait-inside/1", null);
				story.j.waitForMessage("Lock released on resouce [resource1]", b1);

				story.j.waitForMessage("Lock acquired on [resource1]", b2);
				story.j.assertLogContains("Waiting for lock...", b3);
				SemaphoreStep.success("wait-inside/2", null);
				story.j.waitForMessage("Lock acquired on [resource1]", b3);
				SemaphoreStep.success("wait-inside/3", null);
				story.j.waitForMessage("Finish", b3);
			}
		});
	}

	@Test
	public void lockOrderRestart() {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				defineResource("resource1");
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
				story.j.waitForMessage("[resource1] is locked by p#1", b2);
				WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
				// Both 2 and 3 are waiting for locking resource1

				story.j.waitForMessage("[resource1] is locked by p#1", b3);
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
				story.j.waitForMessage("Lock released on resouce [resource1]", b1);

				story.j.waitForMessage("Lock acquired on [resource1]", b2);
				story.j.assertLogContains("Waiting for lock...", b3);
				SemaphoreStep.success("wait-inside/2", null);
				story.j.waitForMessage("Lock acquired on [resource1]", b3);
				SemaphoreStep.success("wait-inside/3", null);
				story.j.waitForMessage("Finish", b3);
			}
		});
	}

	private void defineResource(String r) {
		LockableResourcesManager.get().getResources().add(new LockableResource(r));
		LockableResourcesManager.get().save();
	}

}
