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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;

import static java.lang.Thread.sleep;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public class LockWithResourceVarTest {

	@Rule
	public RestartableJenkinsRule story = new RestartableJenkinsRule();

	@ClassRule
	public static BuildWatcher buildWatcher = new BuildWatcher();

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
								// surface the selected resources in the build displayName
								"	currentBuild.displayName += \" (${env.MY_VAR})\" \n" +
								"}\n" +
								"echo 'Finish'"
				));
				WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
				story.j.waitForCompletion(b1);
				story.j.assertBuildStatus(Result.SUCCESS, b1);
				story.j.assertLogContains("Lock released on resource [resource0, Variable: MY_VAR]", b1);
				story.j.assertLogContains("got resource:[ resource0 ]", b1);

				story.j.assertLogContains("Lock released on resource [Label: res, Quantity: All, Variable: MY_VAR]", b1);
				story.j.assertLogContains("got resources:[ resource", b1);
				for (String resourceName : new String[]{"resource1", "resource2", "resource3"}) {
					assertTrue("Got all resources in second lock step", b1.getDisplayName().contains(resourceName));
				}
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

	@Test public void lockWithVariablesConcurrentRuns() {
		story.addStep(new Statement() {
			@Override
			public void evaluate() throws Throwable {
				int stepIndex = 1;
				LockableResourcesManager.get().createResourceWithLabel("resource1", "res");
				LockableResourcesManager.get().createResourceWithLabel("resource2", "res");
				LockableResourcesManager.get().createResourceWithLabel("resource3", "res");
				LockableResourcesManager.get().createResourceWithLabel("resource4", "res");
				WorkflowJob lockAllJob = UtilFn.createWaitingForResourcesJob(story, "label: 'res'", "lockall");
				WorkflowRun lockAllRun = lockAllJob.scheduleBuild2(0).waitForStart();
				// Run acquires all locks
				story.j.waitForMessage("Lock acquired", lockAllRun);
				WorkflowRun[] runs = new WorkflowRun[8];
				String lockParams = "label: 'res', quantity: 1, variable: 'RESOURCE_VAR'";
				String lockMessage = "Label: res, Quantity: 1, Variable: RESOURCE_VAR";
				for(int i=0; i<=7; i++) {
					WorkflowJob job = UtilFn.createWaitingForResourcesJob(story, lockParams, "resourcejob" + i);
					runs[i] = UtilFn.scheduleBuildAndCheckWaiting(story, job, lockMessage, 1, 0);
				}
				// release all locks
				stepIndex = UtilFn.acquireLockAndFinishBuild(story, lockAllRun, "Label: res, Quantity: All", stepIndex);
				Set<String> acquiredResources = new HashSet<>();
				for (int i=0; i <=3; i++) {
					story.j.waitForMessage("Got resource", runs[i]);
					acquiredResources.add(UtilFn.getBuildResource(runs[i]));
				}
				// first 4 resource runs should have all distinct resources
				assertTrue("All 4 resources acquired", acquiredResources.size() == 4);
				// release semaphore on first 4 jobs and wait to finish
				for (int i=0; i <=3; i++) {
					SemaphoreStep.success("wait-inside/" + stepIndex++, null);
				}
				// make sure first 4 have finished
				for (int i=0; i <=3; i++) {
					story.j.waitForMessage("Finished: SUCCESS", runs[i]);
					story.j.assertBuildStatus(Result.SUCCESS, runs[i]);
				}
				acquiredResources.clear();
				// last 4 resource runs should have resources now
				for (int i=4; i <=7; i++) {
					story.j.waitForMessage("Got resource", runs[i]);
					acquiredResources.add(UtilFn.getBuildResource(runs[i]));
				}
				// last 4 resource runs should have all distinct resources
				assertTrue("All 4 resources acquired", acquiredResources.size() == 4);
				for (int i=4; i <=7; i++) {
					SemaphoreStep.success("wait-inside/" + stepIndex++, null);
				}
				for (int i=4; i <=7; i++) {
					story.j.waitForMessage("Finished: SUCCESS", runs[i]);
					story.j.assertBuildStatus(Result.SUCCESS, runs[i]);
				}
			}
		});
	}
}
