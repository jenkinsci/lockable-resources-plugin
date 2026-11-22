package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class ResourceGroupsRestartTest {
    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void queuedGroupSurvivesRestartAndDoesNotUseFreeCommonResource() throws Throwable {
        System.clearProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE);
        sessions.then(j -> {
            LockableResourcesManager manager = LockableResourcesManager.get();
            LockableResource protectedDevice = new LockableResource("protected");
            protectedDevice.setGroup("production");
            protectedDevice.setLabelsFromString("device");
            LockableResource ordinary = new LockableResource("ordinary");
            ordinary.setLabelsFromString("device");
            manager.setDeclaredResources(List.of(protectedDevice, ordinary));
            manager.reserve(List.of(protectedDevice), "test");
            WorkflowJob job = j.createProject(WorkflowJob.class, "group-restart");
            job.setDefinition(new CpsFlowDefinition("""
                    lock(label: 'device', group: 'production', quantity: 1, variable: 'DEVICE') {
                      echo "selected=${env.DEVICE}"
                      semaphore 'restarted-group'
                    }
                    """, true));
            var run = job.scheduleBuild2(0).waitForStart();
            j.waitForMessage("is reserved", run);
            assertEquals(
                    "production",
                    manager.getCurrentQueuedContext()
                            .get(0)
                            .getResources()
                            .get(0)
                            .getGroup());
            assertTrue(ordinary.isFree());
        });
        sessions.then(j -> {
            LockableResourcesManager manager = LockableResourcesManager.get();
            assertEquals("production", manager.fromName("protected").getGroup());
            assertEquals("common", manager.fromName("ordinary").getGroup());
            assertEquals(
                    "production",
                    manager.getCurrentQueuedContext()
                            .get(0)
                            .getResources()
                            .get(0)
                            .getGroup());
            var job = j.jenkins.getItemByFullName("group-restart", WorkflowJob.class);
            var run = job.getBuildByNumber(1);
            manager.unreserve(List.of(manager.fromName("protected")));
            SemaphoreStep.waitForStart("restarted-group/1", run);
            j.assertLogContains("selected=protected", run);
            assertTrue(manager.fromName("ordinary").isFree());
            SemaphoreStep.success("restarted-group/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(run));
        });
    }
}
