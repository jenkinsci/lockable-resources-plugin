package org.jenkins.plugins.lockableresources;

import hudson.model.*;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.*;

import java.util.Arrays;

import static java.lang.Thread.sleep;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeFalse;

@RunWith(Parameterized.class)
public class FreeStyleTest {

    @Rule
    public RestartableJenkinsRule story = new RestartableJenkinsRule();

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Parameterized.Parameters(name = "Request({0}), LockMessage({1})")
    public static Iterable<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {UtilFn.labelResource("label1"), "resource1"},
                {UtilFn.labelResourceEnvVar("label1", "ENV_VAR"), "resource1"},
                {UtilFn.labelResource("label1", 1), "resource1"},
                {UtilFn.nameResource("resource1"), "resource1"}
        });
    }

    private RequiredResourcesProperty requestedResource;
    private String lockMessage;

    public FreeStyleTest(RequiredResourcesProperty requestedResource, String lockMessage) {
        this.requestedResource = requestedResource;
        this.lockMessage = lockMessage;
    }


    //TODO Sue test for creating resource get processed from queue immediately
    // test that env var is passed back for freestyle tests
    @Test
    public void lockFreestyle() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
                FreeStyleProject job = UtilFn.createWaitingForResourcesFreestyleJob(story, requestedResource, "freestyle");
                FreeStyleBuild b1 = job.scheduleBuild2(0).waitForStart();
                UtilFn.acquireLockAndFinishBuild(story, b1, lockMessage);
            }
        });
    }

    @Test
    public void lockOrderFreestyle() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
                int stepIndex = 1;
                WorkflowJob workflowJob = UtilFn.createWaitingForResourcesJob(story, "'resource1'", "workflow");
                WorkflowRun workflowRun = workflowJob.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-inside/1", workflowRun);
                FreeStyleProject freestyleJob = UtilFn.createWaitingForResourcesFreestyleJob(story, requestedResource, "freestyle");
                Queue.Item freestyleBuild = UtilFn.scheduleBuildAndCheckWaiting(story, freestyleJob, lockMessage, 0, 0);
                stepIndex = UtilFn.acquireLockAndFinishBuild(story, workflowRun, "resource1", stepIndex);
                FreeStyleBuild freeStyleBuild = (FreeStyleBuild) freestyleBuild.getFuture().waitForStart();
                UtilFn.acquireLockAndFinishBuild(story, freeStyleBuild, "resource1");
            }
        });
    }

    @Test
    public void lockFreestyleAfterQueue() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                String resourceName = "resource1";
                String labelName = "label1";
                LockableResourcesManager manager = LockableResourcesManager.get();
                manager.createResourceWithLabel(resourceName, labelName);
                manager.reserve(manager.getResourcesWithLabel(labelName), "testjob");
                FreeStyleProject freestyleJob = UtilFn.createWaitingForResourcesFreestyleJob(story, requestedResource, "freestyle");
                Queue.Item buildItem = UtilFn.scheduleBuildAndCheckWaiting(story, freestyleJob, labelName, 0, 0);
                WorkflowJob workflowJob = UtilFn.createWaitingForResourcesJob(story, "'" + resourceName + "'", "workflow");
                WorkflowRun workflowRun = UtilFn.scheduleBuildAndCheckWaiting(story, workflowJob, lockMessage, 1, 0);
                manager.unreserve(manager.getResourcesWithLabel(labelName));
                UtilFn.acquireLockAndFinishBuild(story, workflowRun, lockMessage, 1);
                FreeStyleBuild freeStyleBuild = (FreeStyleBuild) buildItem.getFuture().waitForStart();
                UtilFn.acquireLockAndFinishBuild(story, freeStyleBuild, lockMessage);
            }
        });
    }

}
