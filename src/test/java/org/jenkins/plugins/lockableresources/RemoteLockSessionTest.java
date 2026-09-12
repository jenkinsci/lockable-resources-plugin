/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jenkins.plugins.lockableresources.remote.RemoteServerFixture;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * What the client does with the answers a server gives it over time.
 *
 * <p>{@link LockStepRemoteTest} covers the happy path: ask, be told it is yours, run the body. This
 * covers the rest of the state machine - waiting and then being promoted, being told no, being told
 * about a lock the server no longer knows, and losing contact with the server for a while.
 *
 * <p>Every one of these ends up somewhere a build can see, which is what the assertions look at: the
 * build result, and whether the body ran. A remote lock that fails has to fail closed, and one that
 * is skipped has to leave the build running - the difference between the two is the whole point.
 */
@WithJenkins
class RemoteLockSessionTest extends LockStepTestBase {

    private static final String LOCK_BODY = """
            lock(resource: 'remote-resource', serverId: 'server-a') {
                echo 'BODY_RAN'
            }
            echo 'AFTER_LOCK'
            """;

    private static RemoteServerFixture startFixture(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemotes(List.of(new RemoteConnection("server-a", remote.baseUrl(), "")));
        manager.setClientId("client-jenkins-a");
        return remote;
    }

    private static WorkflowRun run(JenkinsRule j, String name, String script) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, true));
        return job.scheduleBuild2(0).waitForStart();
    }

    @Test
    void queuedRequestRunsTheBodyOncePromoted(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = startFixture(j);
        try {
            // Three polls report QUEUED before the server hands the resource over. The body must not
            // start until it does - a client that ran on QUEUED would be running unlocked.
            remote.queueFor(3);

            WorkflowRun run = run(j, "queued-then-acquired", LOCK_BODY);
            j.waitForCompletion(run);

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("BODY_RAN", run);
            assertTrue(remote.statusRequests.get() >= 4, "polled while queued, then once more to be promoted");
            assertEquals(1, remote.releaseRequests.get(), "released on the way out");
        } finally {
            remote.stop();
        }
    }

    @Test
    void terminalFailureFailsTheBuildWithoutRunningTheBody(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = startFixture(j);
        try {
            // What a server reports when the request waited out its allocate timeout.
            remote.terminal("FAILED", "LOCK_WAIT_TIMEOUT");

            WorkflowRun run = run(j, "terminal-failed", LOCK_BODY);
            j.waitForCompletion(run);

            j.assertBuildStatus(hudson.model.Result.FAILURE, run);
            j.assertLogNotContains("BODY_RAN", run);
            j.assertLogContains("LOCK_WAIT_TIMEOUT", run);
        } finally {
            remote.stop();
        }
    }

    @Test
    void skippedRequestLeavesTheBuildRunning(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = startFixture(j);
        try {
            // skipIfLocked: the body is skipped, the build carries on. Failing here would turn an
            // intentional skip into a broken build.
            remote.terminal("SKIPPED", "ALREADY_LOCKED");

            WorkflowRun run = run(j, "terminal-skipped", """
                    lock(resource: 'remote-resource', serverId: 'server-a', skipIfLocked: true) {
                        echo 'BODY_RAN'
                    }
                    echo 'AFTER_LOCK'
                    """);
            j.waitForCompletion(run);

            j.assertBuildStatusSuccess(run);
            j.assertLogNotContains("BODY_RAN", run);
            j.assertLogContains("AFTER_LOCK", run);
        } finally {
            remote.stop();
        }
    }

    @Test
    void aLockTheServerNoLongerKnowsIsReportedAsMissingNotAsATimeout(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = startFixture(j);
        try {
            // 404 means the record is genuinely gone - the server restarted, or the record outlived
            // its TTL. Calling that a timeout would send the reader looking for contention that never
            // happened, which is the mislabelling A3 was about.
            remote.failAllPolls(404, "{\"errorCode\":\"LOCK_NOT_FOUND\",\"message\":\"Lock not found\"}");

            WorkflowRun run = run(j, "record-gone", LOCK_BODY);
            j.waitForCompletion(run);

            j.assertBuildStatus(hudson.model.Result.FAILURE, run);
            j.assertLogNotContains("BODY_RAN", run);
            j.assertLogContains("server may have restarted", run);
            j.assertLogNotContains("LOCK_WAIT_TIMEOUT", run);
        } finally {
            remote.stop();
        }
    }

    @Test
    void aStaleLeaseIsReportedAsMissingToo(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = startFixture(j);
        try {
            remote.failAllPolls(410, "{\"errorCode\":\"LOCK_STALE\",\"message\":\"Lock is STALE\"}");

            WorkflowRun run = run(j, "record-stale", LOCK_BODY);
            j.waitForCompletion(run);

            j.assertBuildStatus(hudson.model.Result.FAILURE, run);
            j.assertLogNotContains("BODY_RAN", run);
            j.assertLogContains("server may have restarted", run);
        } finally {
            remote.stop();
        }
    }

    @Test
    void aBriefLossOfContactIsRiddenOut(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = startFixture(j);
        try {
            // A handful of failed polls is a blip, not an answer. The client has to keep asking:
            // giving up here would fail builds over a server that was busy for ten seconds.
            remote.failNextPolls(3, 500, "{\"errorCode\":\"BOOM\",\"message\":\"transient\"}");

            WorkflowRun run = run(j, "transient-poll-failure", LOCK_BODY);
            j.waitForCompletion(run);

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("BODY_RAN", run);
        } finally {
            remote.stop();
        }
    }

    @Test
    void aServerThatNeverComesBackEndsTheBuildFailClosed(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = startFixture(j);
        try {
            // The other side of the previous test: past the client's tolerance, it has to stop. This
            // is the only threshold at which the client gives up on its own, and it takes about a
            // minute of real time to reach - the poll interval and the failure count are both fixed.
            remote.failAllPolls(500, "{\"errorCode\":\"BOOM\",\"message\":\"gone for good\"}");

            WorkflowRun run = run(j, "permanent-poll-failure", LOCK_BODY);
            j.waitForCompletion(run);

            j.assertBuildStatus(hudson.model.Result.FAILURE, run);
            j.assertLogNotContains("BODY_RAN", run);
            assertTrue(
                    remote.statusRequests.get() >= 20,
                    "gave up only after the full run of failures, not on the first one: "
                            + remote.statusRequests.get());
        } finally {
            remote.stop();
        }
    }

    @Test
    void aFailingHeartbeatDoesNotInterruptTheBody(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = startFixture(j);
        try {
            // A heartbeat renews a lease; losing one is not losing the lock. The server holds it until
            // STALE, so aborting the build here would throw away work the build still owns.
            remote.setHeartbeatResponse(410, "{\"errorCode\":\"LOCK_STALE\",\"message\":\"Lock is STALE\"}");

            WorkflowRun run = run(j, "heartbeat-failure", """
                    lock(resource: 'remote-resource', serverId: 'server-a') {
                        echo 'BODY_RAN'
                        sleep 12
                        echo 'BODY_FINISHED'
                    }
                    """);
            j.waitForCompletion(run);

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("BODY_FINISHED", run);
            assertTrue(remote.heartbeatRequests.get() >= 1, "the heartbeat was attempted and refused");
        } finally {
            remote.stop();
        }
    }
}
