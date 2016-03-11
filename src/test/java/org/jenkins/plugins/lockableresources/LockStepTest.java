package org.jenkins.plugins.lockableresources;


import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.RestartableJenkinsRule;

public class LockStepTest {

	@Rule
	public RestartableJenkinsRule story = new RestartableJenkinsRule();

	@Test
	public void lockOrder() {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition(
						"echo 'Start'\n" +
						"semaphore 'wait'\n" +
						"lock('resource1') {\n" +
						"	semaphore 'wait-inside'\n" +
						"}\n" +
						"echo 'Finish'"
				));
				WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
				SemaphoreStep.success("wait/1", null);
				SemaphoreStep.waitForStart("wait-inside/1", b1);

				WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
				SemaphoreStep.waitForStart("wait/2", b2);
				WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
				SemaphoreStep.waitForStart("wait/3", b3);

				// Let's b3 reach the lock before
				SemaphoreStep.success("wait/3", null);
				SemaphoreStep.success("wait/2", null);

				SemaphoreStep.success("wait-inside/1", null);

				System.out.println(b3.getLog());
				story.j.assertLogContains("acquired", b3);
			}
		});
	}

}
