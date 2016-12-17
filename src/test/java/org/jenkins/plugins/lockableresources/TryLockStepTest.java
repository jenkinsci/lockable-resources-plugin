package org.jenkins.plugins.lockableresources;

import hudson.model.Result;
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;
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

public class TryLockStepTest {
    @Rule
    public RestartableJenkinsRule story = new RestartableJenkinsRule();
    @ClassRule
    public static final BuildWatcher buildWatcher = new BuildWatcher();

    @Test
    public void trylock_ok_result() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResource("resource1", "label1");
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "a = trylock(resource: 'resource1') {\n"
                        + "	echo 'yeeees'\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'a=' + a"
                ));
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-inside/1", b1);
                SemaphoreStep.success("wait-inside/1", null);
                story.j.waitForCompletion(b1);
                story.j.assertBuildStatus(Result.SUCCESS, b1);
                story.j.assertLogContains("yeeees", b1);
                story.j.assertLogContains("a=true", b1);
            }
        });
    }
    @Test
    public void trylock_nok_result() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResource("resource1", "label1");
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        "a = trylock(label: 'label1', quantity: 2) {\n"
                        + "	echo 'yeeees'\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'a=' + a"
                ));
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                story.j.waitForCompletion(b1);
                story.j.assertBuildStatus(Result.SUCCESS, b1);
                story.j.assertLogNotContains("yeeees", b1);
                story.j.assertLogContains("a=false", b1);
            }
        });
    }
}
