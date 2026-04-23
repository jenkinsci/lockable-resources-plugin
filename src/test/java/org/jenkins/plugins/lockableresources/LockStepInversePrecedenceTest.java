package org.jenkins.plugins.lockableresources;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for inversePrecedence queue ordering (issues #861 and #864).
 *
 * <p>Extracted from {@link LockStepTest} to keep test classes small and avoid CI timeouts.
 */
@WithJenkins
class LockStepInversePrecedenceTest extends LockStepTestBase {

    /**
     * Verify that inversePrecedence=true grants the lock to the newest build
     * when locking by <b>label</b> (not named resource).
     *
     * <pre>
     * start time | build | label  | inversePrecedence
     * -----------|-------|--------|-------------------
     * 00:01      | b1    | label1 | true   (acquires)
     * 00:02      | b2    | label1 | true   (waits)
     * 00:03      | b3    | label1 | true   (waits)
     *
     * expected lock order: b1 -> b3 -> b2
     * </pre>
     */
    @Test
    @Issue({"JENKINS-40787", "GITHUB-861"})
    @Disabled("Blocked by #861 — inversePrecedence is not applied for label-based locks, test hangs")
    void lockInverseOrderWithLabel(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                lock(label: 'label1', inversePrecedence: true) {
                    semaphore 'wait-inside'
                }
                echo 'Finish'""", true));

        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Label: label1] is locked by build " + b1.getFullDisplayName(), b2);
        isPaused(b2, 1, 1);

        WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Label: label1] is locked by build " + b1.getFullDisplayName(), b3);
        isPaused(b3, 1, 1);

        // Release b1 — b3 (newest) must acquire before b2
        SemaphoreStep.success("wait-inside/1", null);
        j.waitForMessage("Lock released on resource", b1);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        SemaphoreStep.waitForStart("wait-inside/2", b3);
        j.waitForMessage("Trying to acquire lock on [Label: label1]", b3);

        SemaphoreStep.success("wait-inside/2", null);
        j.waitForMessage("Lock released on resource", b3);
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));

        SemaphoreStep.waitForStart("wait-inside/3", b2);
        j.waitForMessage("Trying to acquire lock on [Label: label1]", b2);

        SemaphoreStep.success("wait-inside/3", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
    }

    /**
     * Verify that each waiting job's own {@code inversePrecedence} flag controls
     * queue ordering, not the releasing job's flag. Uses <b>separate</b> pipeline
     * jobs to match the original report.
     *
     * <pre>
     * start time | job  | resource  | inversePrecedence
     * -----------|------|-----------|-------------------
     * 00:01      | pA#1 | resource1 | true   (acquires)
     * 00:02      | pB#1 | resource1 | false  (waits — FIFO)
     * 00:03      | pA#2 | resource1 | true   (waits — inversePrecedence, front)
     * 00:04      | pB#2 | resource1 | false  (waits — FIFO, behind pB#1)
     *
     * expected lock order: pA#1 -> pA#2 -> pB#1 -> pB#2
     * </pre>
     */
    @Test
    @Issue({"JENKINS-41070", "GITHUB-864"})
    void lockInverseOrderMixedDifferentJobs(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");

        // Job A — inversePrecedence = true
        WorkflowJob pA = j.jenkins.createProject(WorkflowJob.class, "pA");
        pA.setDefinition(new CpsFlowDefinition("""
                lock(resource: 'resource1', inversePrecedence: true) {
                    echo 'locked-pA'
                    semaphore 'wait-inside'
                }
                echo 'Finish'""", true));

        // Job B — inversePrecedence = false
        WorkflowJob pB = j.jenkins.createProject(WorkflowJob.class, "pB");
        pB.setDefinition(new CpsFlowDefinition("""
                lock(resource: 'resource1', inversePrecedence: false) {
                    echo 'locked-pB'
                    semaphore 'wait-inside'
                }
                echo 'Finish'""", true));

        // pA#1 acquires the lock
        WorkflowRun a1 = pA.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", a1);
        j.assertLogContains("locked-pA", a1);

        // pB#1 waits (inversePrecedence=false → back of queue)
        WorkflowRun b1 = pB.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + a1.getFullDisplayName(), b1);

        // pA#2 waits (inversePrecedence=true → front of queue)
        WorkflowRun a2 = pA.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + a1.getFullDisplayName(), a2);

        // pB#2 waits (inversePrecedence=false → back of queue, behind pB#1)
        WorkflowRun b2 = pB.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + a1.getFullDisplayName(), b2);

        // Verify only a1 has the lock so far
        j.assertLogNotContains("locked-pA", a2);
        j.assertLogNotContains("locked-pB", b1);
        j.assertLogNotContains("locked-pB", b2);

        // Release pA#1 — pA#2 (inversePrecedence=true) must acquire next
        SemaphoreStep.success("wait-inside/1", null);
        j.waitForMessage("Lock released on resource", a1);

        SemaphoreStep.waitForStart("wait-inside/2", a2);
        j.assertLogContains("locked-pA", a2);
        j.assertLogNotContains("locked-pB", b1);
        j.assertLogNotContains("locked-pB", b2);

        // Release pA#2 — pB#1 (FIFO among false) must acquire next
        SemaphoreStep.success("wait-inside/2", null);
        j.waitForMessage("Lock released on resource", a2);

        SemaphoreStep.waitForStart("wait-inside/3", b1);
        j.assertLogContains("locked-pB", b1);
        j.assertLogNotContains("locked-pB", b2);

        // Release pB#1 — pB#2 gets the lock last
        SemaphoreStep.success("wait-inside/3", null);
        j.waitForMessage("Lock released on resource", b1);

        SemaphoreStep.waitForStart("wait-inside/4", b2);
        j.assertLogContains("locked-pB", b2);

        // Release pB#2 and verify all succeed
        SemaphoreStep.success("wait-inside/4", null);

        j.assertBuildStatusSuccess(j.waitForCompletion(a1));
        j.assertBuildStatusSuccess(j.waitForCompletion(a2));
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
    }
}
