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
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

/**
 * What happens to a remote lock when the client controller restarts underneath it.
 *
 * <p>A remote lock is held on another controller, which knows nothing about this one going down. The
 * two sides can therefore disagree in a way a local lock never can: the server goes on holding a
 * resource for a build that no longer exists. Everything here is about not leaving it that way.
 *
 * <p>The fixture lives outside the sessions and is restarted on its own port, because the resumed
 * controller has to find the remote at the address its configuration already records - a fixture on
 * a new port would only prove that a client cannot reach a server that moved.
 */
class RemoteLockRestartTest extends LockStepTestBase {

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void aQueuedRequestKeepsWaitingAcrossARestart() throws Throwable {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        try {
            // Enough queued polls that the request is still waiting when the controller goes down.
            remote.queueFor(1000);

            sessions.then(j -> {
                LockableResourcesManager manager = LockableResourcesManager.get();
                manager.setRemotes(List.of(new RemoteConnection("server-a", remote.baseUrl(), "")));
                manager.setClientId("client-jenkins-a");

                WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "queued-across-restart");
                p.setDefinition(new CpsFlowDefinition("""
                        lock(resource: 'remote-resource', serverId: 'server-a') {
                            echo 'BODY_RAN'
                        }
                        echo 'AFTER_LOCK'
                        """, true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                j.waitForMessage("Trying to acquire", b);
            });

            remote.restartOnSamePort();

            sessions.then(j -> {
                WorkflowJob p = j.jenkins.getItemByFullName("queued-across-restart", WorkflowJob.class);
                WorkflowRun b = p.getBuildByNumber(1);

                // Stop queueing, so the next poll hands the resource over. The body running is the
                // proof that the step resumed polling at all: nothing else would ever ask again, and
                // the build would sit here until the test timed out.
                remote.queueFor(0);

                j.waitForCompletion(b);
                j.assertBuildStatusSuccess(b);
                j.assertLogContains("BODY_RAN", b);
                assertEquals(1, remote.releaseRequests.get(), "released once, after the resumed body finished");
            });
        } finally {
            remote.stop();
        }
    }

    @Test
    void aHeldLockIsReleasedWhenTheBodyDiesWithTheController() throws Throwable {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        try {
            sessions.then(j -> {
                LockableResourcesManager manager = LockableResourcesManager.get();
                manager.setRemotes(List.of(new RemoteConnection("server-a", remote.baseUrl(), "")));
                manager.setClientId("client-jenkins-a");

                WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "held-across-restart");
                p.setDefinition(new CpsFlowDefinition("""
                        lock(resource: 'remote-resource', serverId: 'server-a') {
                            semaphore 'inside-remote-lock'
                        }
                        echo 'AFTER_LOCK'
                        """, true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("inside-remote-lock/1", b);
            });

            remote.restartOnSamePort();
            int releasesBefore = remote.releaseRequests.get();

            sessions.then(j -> {
                WorkflowJob p = j.jenkins.getItemByFullName("held-across-restart", WorkflowJob.class);
                WorkflowRun b = p.getBuildByNumber(1);

                // The body cannot survive the restart, so the lock it was holding has to go back. If it
                // did not, the server would hold that resource for a build that no longer exists, and
                // only an administrator could get it back.
                j.waitForCompletion(b);
                j.assertBuildStatus(hudson.model.Result.FAILURE, b);
                j.assertLogContains("Jenkins restarted during remote lock body execution", b);
                j.assertLogNotContains("AFTER_LOCK", b);
                assertTrue(
                        remote.releaseRequests.get() > releasesBefore,
                        "the remote lock was released on the way down, not left held");
            });
        } finally {
            remote.stop();
        }
    }

    @Test
    void waitingOnAPausedServerDoesNotSurviveARestart() throws Throwable {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        try {
            // The server is in maintenance, so the client is sitting in its retry loop with no lock
            // anywhere - not on this side, and not on the server's.
            remote.pauseNextAcquires(1000);

            sessions.then(j -> {
                LockableResourcesManager manager = LockableResourcesManager.get();
                manager.setRemotes(List.of(new RemoteConnection("server-a", remote.baseUrl(), "")));
                manager.setClientId("client-jenkins-a");

                WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "paused-across-restart");
                p.setDefinition(new CpsFlowDefinition("""
                        lock(resource: 'remote-resource', serverId: 'server-a') {
                            echo 'BODY_RAN'
                        }
                        """, true));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                j.waitForMessage("not accepting new acquire requests", b);
            });

            remote.restartOnSamePort();

            sessions.then(j -> {
                WorkflowJob p = j.jenkins.getItemByFullName("paused-across-restart", WorkflowJob.class);
                WorkflowRun b = p.getBuildByNumber(1);

                // The retry loop did not survive the restart. Failing is the honest outcome: waiting
                // forever for a retry that will never happen would be worse than saying so.
                j.waitForCompletion(b);
                j.assertBuildStatus(hudson.model.Result.FAILURE, b);
                j.assertLogNotContains("BODY_RAN", b);
                j.assertLogContains("Jenkins restarted while waiting for the remote server", b);
            });
        } finally {
            remote.stop();
        }
    }
}
