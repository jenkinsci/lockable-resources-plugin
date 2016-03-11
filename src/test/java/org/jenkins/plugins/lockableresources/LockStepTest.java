package org.jenkins.plugins.lockableresources;


import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.RestartableJenkinsRule;

public class LockStepTest {

	@Rule
	public RestartableJenkinsRule story = new RestartableJenkinsRule();

	public void lockOrder() {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
				p.setDefinition(new CpsFlowDefinition(
						"echo 'Start'\n" +
						"lock('resource1') {\n" +
						"	semaphore 'wait'\n" +
						"}\n" +
						"echo 'Finish'"
				));
				WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
			}
		});
	}

}
