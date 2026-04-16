/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Test that jobs waiting for resources can pick up newly added resources.
 * See JENKINS-46744 / issue #892.
 *
 * <p>Note: The lock step validates that at least one resource with the given
 * label exists. If no resource has the label, it fails. Therefore we need
 * an existing resource (locked by job1) before job2 can wait for a resource
 * with that label.</p>
 */
@WithJenkins
class NewResourceUnblocksWaitingJobTest extends LockStepTestBase {

    /**
     * Test that a pipeline job waiting for a resource by label can acquire
     * a newly added resource that has the matching label. (JENKINS-46744)
     *
     * Scenario:
     * 1. Job1 locks r1 (the only resource with label "test-label")
     * 2. Job2 tries to lock a resource with "test-label" - it waits
     * 3. A new resource "r2" with label "test-label" is added
     * 4. Job2 should acquire "r2" without waiting for Job1 to finish
     */
    @Issue("JENKINS-46744")
    @Test
    void newResourceWithLabelUnblocksWaitingPipelineJob(JenkinsRule j) throws Exception {
        // Create resource r1 that will be locked by job1
        LockableResourcesManager lrm = LockableResourcesManager.get();
        assertTrue(lrm.createResourceWithLabel("r1", "test-label"));

        // Job1: locks r1 and holds it via semaphore
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "job1");
        job1.setDefinition(new CpsFlowDefinition("""
            lock(label: 'test-label', quantity: 1, variable: 'LOCKED') {
                echo("Job1 locked: ${env.LOCKED}")
                semaphore('hold-lock')
            }
            """, true));

        // Job2: tries to lock a resource with test-label
        WorkflowJob job2 = j.jenkins.createProject(WorkflowJob.class, "job2");
        job2.setDefinition(new CpsFlowDefinition("""
            timeout(time: 60, unit: 'SECONDS') {
                lock(label: 'test-label', quantity: 1, variable: 'LOCKED') {
                    echo("Job2 locked: ${env.LOCKED}")
                }
            }
            """, true));

        // Start job1 - it will lock r1 and wait at semaphore
        WorkflowRun run1 = job1.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Job1 locked: r1", run1);

        // Start job2 - it should wait because r1 (the only resource) is locked
        WorkflowRun run2 = job2.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution", run2);

        // Now add a new resource with the same label
        // This should automatically trigger proceedNextContext() (JENKINS-46744)
        assertTrue(lrm.createResourceWithLabel("r2", "test-label"));

        // The job should now proceed and acquire r2
        j.waitForMessage("Job2 locked: r2", run2);
        j.assertBuildStatusSuccess(j.waitForCompletion(run2));

        // Now let job1 finish
        SemaphoreStep.success("hold-lock/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(run1));
    }

    /**
     * Test that a freestyle job waiting for a resource by label can acquire
     * a newly added resource. (JENKINS-46744)
     */
    @Issue("JENKINS-46744")
    @Test
    void newResourceUnblocksWaitingFreestyleJob(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        // Create resource r1 that will be locked by job1
        assertTrue(lrm.createResourceWithLabel("r1", "test-label"));

        // Job1: locks r1 and sleeps (holds the lock)
        FreeStyleProject job1 = j.createFreeStyleProject("freestyle-job1");
        job1.addProperty(new RequiredResourcesProperty(null, null, "1", "test-label", null));
        job1.getBuildersList().add(new org.jvnet.hudson.test.SleepBuilder(10000));

        // Job2: wants test-label
        FreeStyleProject job2 = j.createFreeStyleProject("freestyle-job2");
        job2.addProperty(new RequiredResourcesProperty(null, null, "1", "test-label", null));

        // Start job1 - it will lock r1 and sleep
        FreeStyleBuild build1 = job1.scheduleBuild2(0).waitForStart();
        j.waitForMessage("acquired lock on [r1]", build1);

        // Start job2 - it should wait in queue because r1 is locked
        var future2 = job2.scheduleBuild2(0);
        Thread.sleep(1000); // Give time for job2 to enter queue

        // Now add a new resource with the same label
        // This should trigger scheduleQueueMaintenance() (JENKINS-46744)
        assertTrue(lrm.createResourceWithLabel("r2", "test-label"));

        // Job2 should now get dispatched and acquire r2
        FreeStyleBuild build2 = future2.waitForStart();
        j.waitForMessage("acquired lock on [r2]", build2);
        j.waitForCompletion(build2);

        // Job1 will finish on its own after sleeping
        j.waitForCompletion(build1);
    }
}
