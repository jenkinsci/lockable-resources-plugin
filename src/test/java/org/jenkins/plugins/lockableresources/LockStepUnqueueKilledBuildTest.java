package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import hudson.model.Result;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jenkins.plugins.lockableresources.queue.QueuedContextStruct;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LockStepUnqueueKilledBuildTest extends LockStepTestBase {

    @Issue("614")
    @Test
    void cancelledBuildIsRemovedFromQueue(JenkinsRule j) throws Exception {
        WorkflowRun[] builds = startQueuedBuilds(j);
        WorkflowRun b1 = builds[0];
        WorkflowRun b2 = builds[1];

        b1.doStop();
        j.waitForCompletion(b1);
        j.assertBuildStatus(Result.ABORTED, b1);

        assertOnlyOtherBuildQueued(b2);
        cleanup(j, b2);
    }

    @Issue("614")
    @Test
    void hardKilledBuildIsRemovedFromQueue(JenkinsRule j) throws Exception {
        WorkflowRun[] builds = startQueuedBuilds(j);
        WorkflowRun b1 = builds[0];
        WorkflowRun b2 = builds[1];

        b1.doKill();
        j.waitForMessage("Hard kill!", b1);
        j.waitForCompletion(b1);
        j.assertBuildStatus(Result.ABORTED, b1);

        // nothing else triggers queue processing here, so the dead entries must be gone already
        assertOnlyOtherBuildQueued(b2);
        cleanup(j, b2);
    }

    /**
     * Reserves resource A, starts b1 with 3 parallel branches waiting on A, and b2 with one more
     * lock('A') waiting behind them.
     */
    private static WorkflowRun[] startQueuedBuilds(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("A");
        lrm.reserve(Collections.singletonList(lrm.fromName("A")), "someone");

        WorkflowJob p1 = j.jenkins.createProject(WorkflowJob.class, "p1");
        p1.setDefinition(new CpsFlowDefinition("""
                parallel(
                  a: { lock('A') { sleep 600 } },
                  b: { lock('A') { sleep 600 } },
                  c: { lock('A') { sleep 600 } }
                )""", true));
        WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
        waitForQueueSize(3);

        WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
        p2.setDefinition(new CpsFlowDefinition("lock('A') { echo 'p2 inside' }", true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        waitForQueueSize(4);

        return new WorkflowRun[] {b1, b2};
    }

    private static void assertOnlyOtherBuildQueued(WorkflowRun other) {
        List<QueuedContextStruct> queue = queueSnapshot();
        assertEquals(1, queue.size(), "queue: " + queue);
        assertSame(other, queue.get(0).getBuild());
    }

    private static void cleanup(JenkinsRule j, WorkflowRun b2) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.unreserve(Collections.singletonList(lrm.fromName("A")));
        j.waitForMessage("p2 inside", b2);
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        assertEquals(0, queueSnapshot().size());
    }

    private static List<QueuedContextStruct> queueSnapshot() {
        synchronized (LockableResourcesManager.syncResources) {
            return new ArrayList<>(LockableResourcesManager.get().getCurrentQueuedContext());
        }
    }

    private static void waitForQueueSize(int expected) throws InterruptedException {
        for (int i = 0; i < 600 && queueSnapshot().size() != expected; i++) {
            Thread.sleep(100);
        }
        assertEquals(expected, queueSnapshot().size());
    }
}
