/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.LockStep;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.RemoteConnection;
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Client-side state machine for a remote {@code lock()}: enqueue the acquire request, short-poll the remote
 * server for the outcome, heartbeat while the body runs, and release on completion. A serializable
 * collaborator of the {@code lock()} step execution.
 *
 * <p>The session owns the transport/scheduling state (poll &amp; heartbeat timers, retry budget, the
 * persisted {@code serverId}/{@code lockId}/state). Everything that requires the workflow step's own context
 * - running the lock body, signalling success/failure - is delegated to a {@link Host} (implemented by
 * {@code LockStepExecution}). The session is serialized as a field of the step execution, so it survives a
 * controller restart; the timers are {@code transient} and rebuilt by {@link #onResume(Host)}.
 */
@Restricted(NoExternalUse.class)
public final class RemoteLockSession implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(RemoteLockSession.class.getName());

    private static final int MAX_CONSECUTIVE_POLL_FAILURES = 20; // ~60s at 3s poll interval

    /**
     * Step-side integration the session calls back into. Implemented by {@code LockStepExecution}; kept
     * minimal so the bulk of the remote flow lives here rather than in the core step class.
     */
    public interface Host extends Serializable {
        /** The workflow step context (for {@code Run}/{@code FlowNode}/{@code TaskListener}, onSuccess/onFailure). */
        StepContext context();

        /** The originating {@code lock()} step. */
        LockStep step();

        /** Runs the lock body; on completion the framework releases the remote lock via {@link #onBodyFinished(Host)}. */
        void runBody(String displayTarget, @CheckForNull Map<String, String> lockEnvVars, String lockId);
    }

    private final AtomicBoolean completionSignaled = new AtomicBoolean(false);

    private transient volatile ScheduledFuture<?> pollTask;
    /** Set while the remote answers 503 ACQUIRES_PAUSED and we are waiting for it to reopen. */
    private volatile boolean acquirePaused;
    /** Wall-clock deadline for the acquire retries, or 0 when the request waits forever. */
    private volatile long acquireDeadlineMillis;

    private transient volatile ScheduledFuture<?> acquireRetryTask;
    private transient volatile ScheduledFuture<?> heartbeatTask;

    private volatile String serverId;
    private volatile String lockId;
    /** What was asked for, kept so the page (and a resumed session) can still name it. */
    private volatile String requestDescription;

    private volatile RemoteAcquireState lastState = RemoteAcquireState.UNKNOWN;
    private volatile boolean bodyStarted;
    private volatile int consecutivePollFailures;

    /** @return the remote lockId once enqueued (used by the step for resume detection); may be {@code null}. */
    @CheckForNull
    public String getLockId() {
        return lockId;
    }

    /** @return the target serverId once the acquire has been enqueued; may be {@code null}. */
    @CheckForNull
    public String getServerId() {
        return serverId;
    }

    /** Fails the session (fail-closed; no release) - used by the host when running the body throws. */
    public void fail(Host host, Throwable cause) {
        finishFailure(host, cause);
    }

    // ---------------------------------------------------------------------------
    public boolean start(Host host) throws Exception {
        LockStep step = host.step();
        StepContext context = host.context();
        PrintStream logger = context.get(TaskListener.class).getLogger();
        Run<?, ?> run = context.get(Run.class);
        LockableResourcesManager lrm = LockableResourcesManager.get();

        String displayTarget = RemoteLockRouting.displayTarget(step);
        String effectiveServerId = RemoteLockRouting.effectiveServerId(step, lrm, logger);
        RemoteConnection remote = RemoteLockRouting.findConnection(lrm, effectiveServerId);
        String authorizationHeader = RemoteCredentials.basicAuthHeader(remote, run);
        RemoteApiClient client = new RemoteApiClient();
        RemoteLockRequest lockRequest = RemoteLockRequest.from(step);

        LockableResourcesManager.printLogs(
                "Trying to acquire remote lock on ["
                        + step
                        + "] (serverId="
                        + remote.getServerId()
                        + ", serverUrl="
                        + remote.getUrl()
                        + ")",
                Level.FINE,
                LOGGER,
                logger);
        context.get(FlowNode.class).addAction(new PauseAction("Lock"));

        // Use configured clientId (or root URL as fallback) to identify this Jenkins to the remote server.
        String clientId = lrm.getEffectiveClientId();
        this.acquireDeadlineMillis = acquireDeadline(lockRequest);
        try {
            enqueueAcquire(host, remote, authorizationHeader, client, run, displayTarget, lockRequest, clientId);
        } catch (RemoteApiException ex) {
            if (ex.getHttpStatus() == 503) {
                // The server is paused for maintenance, not broken. Failing the build here is exactly what
                // the switch exists to avoid, so keep asking until this request's own allocation timeout.
                acquirePaused = true;
                logger.println("Remote server is not accepting new acquire requests right now (serverId="
                        + remote.getServerId()
                        + ", serverUrl="
                        + remote.getUrl()
                        + "); waiting for it to resume.");
                scheduleAcquireRetry(
                        host, remote, authorizationHeader, client, run, displayTarget, lockRequest, clientId);
                return false;
            }
            logger.println("Remote lock request failed (serverId="
                    + remote.getServerId()
                    + ", serverUrl="
                    + remote.getUrl()
                    + "): "
                    + ex.getMessage());
            finishFailure(host, ex);
        }
        return false;
    }

    /** Sends the acquire request and, on acceptance, starts polling for its outcome. */
    private void enqueueAcquire(
            Host host,
            RemoteConnection remote,
            String authorizationHeader,
            RemoteApiClient client,
            Run<?, ?> run,
            String displayTarget,
            RemoteLockRequest lockRequest,
            String clientId)
            throws RemoteApiException {
        String acquiredLockId = client.enqueueAcquire(
                remote,
                authorizationHeader,
                lockRequest,
                RemoteClientDefaults.DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
                clientId);
        this.serverId = remote.getServerId();
        this.lockId = acquiredLockId;
        this.acquirePaused = false;
        this.requestDescription = displayTarget;
        RemoteClientRegistry.get().queued(acquiredLockId, remote.getServerId(), displayTarget, run);
        LOGGER.log(Level.FINE, "Remote acquire enqueued: serverId={0}, serverUrl={1}, lockId={2}", new Object[] {
            remote.getServerId(), remote.getUrl(), acquiredLockId
        });
        startPolling(host, remote, authorizationHeader, client, run, displayTarget);
    }

    /** Deadline for the acquire retries, mirroring the allocation timeout the request carries. */
    private static long acquireDeadline(RemoteLockRequest lockRequest) {
        long timeout = lockRequest.getTimeoutForAllocateResource();
        if (timeout <= 0) {
            return 0; // wait forever, like a local lock() without a timeout
        }
        try {
            TimeUnit unit = TimeUnit.valueOf(lockRequest.getTimeoutUnit().toUpperCase(Locale.ENGLISH));
            return System.currentTimeMillis() + unit.toMillis(timeout);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private void scheduleAcquireRetry(
            Host host,
            RemoteConnection remote,
            String authorizationHeader,
            RemoteApiClient client,
            Run<?, ?> run,
            String displayTarget,
            RemoteLockRequest lockRequest,
            String clientId) {
        cancelAcquireRetryTask();
        acquireRetryTask = jenkins.util.Timer.get()
                .scheduleWithFixedDelay(
                        () -> retryAcquire(
                                host, remote, authorizationHeader, client, run, displayTarget, lockRequest, clientId),
                        RemoteClientDefaults.DEFAULT_POLL_INTERVAL_SECONDS,
                        RemoteClientDefaults.DEFAULT_POLL_INTERVAL_SECONDS,
                        TimeUnit.SECONDS);
    }

    @SuppressFBWarnings(
            value = "REC_CATCH_EXCEPTION",
            justification = "The retry loop must not propagate anything to the executor.")
    private void retryAcquire(
            Host host,
            RemoteConnection remote,
            String authorizationHeader,
            RemoteApiClient client,
            Run<?, ?> run,
            String displayTarget,
            RemoteLockRequest lockRequest,
            String clientId) {
        if (completionSignaled.get()) {
            cancelAcquireRetryTask();
            return;
        }
        try {
            enqueueAcquire(host, remote, authorizationHeader, client, run, displayTarget, lockRequest, clientId);
            cancelAcquireRetryTask();
        } catch (RemoteApiException ex) {
            if (ex.getHttpStatus() == 503) {
                if (acquireDeadlineMillis > 0 && System.currentTimeMillis() >= acquireDeadlineMillis) {
                    cancelAcquireRetryTask();
                    finishFailure(
                            host,
                            new AbortException("Remote acquire timed out while the server was not accepting new "
                                    + "acquire requests (serverId=" + serverId(remote) + ", state=FAILED, "
                                    + "errorCode=ACQUIRES_PAUSED)"));
                }
                return; // still paused and still within the budget - keep waiting
            }
            cancelAcquireRetryTask();
            finishFailure(host, ex);
        } catch (Exception ex) {
            cancelAcquireRetryTask();
            finishFailure(host, ex);
        }
    }

    private String serverId(RemoteConnection remote) {
        return serverId != null ? serverId : remote.getServerId();
    }

    private void cancelAcquireRetryTask() {
        ScheduledFuture<?> task = acquireRetryTask;
        if (task != null) {
            task.cancel(false);
            acquireRetryTask = null;
        }
    }

    private void startPolling(
            Host host,
            RemoteConnection remote,
            String authorizationHeader,
            RemoteApiClient client,
            Run<?, ?> run,
            String remoteResource) {
        cancelPollTask();
        pollTask = jenkins.util.Timer.get()
                .scheduleWithFixedDelay(
                        () -> pollStatus(host, remote, authorizationHeader, client, run, remoteResource),
                        0,
                        RemoteClientDefaults.DEFAULT_POLL_INTERVAL_SECONDS,
                        TimeUnit.SECONDS);
    }

    @SuppressFBWarnings(
            value = {"REC_CATCH_EXCEPTION", "VO_VOLATILE_INCREMENT"},
            justification = "Poll loop must not propagate any exception; consecutivePollFailures is mutated only"
                    + " on the single-threaded poll executor (volatile is for visibility to onResume).")
    private void pollStatus(
            Host host,
            RemoteConnection remote,
            String authorizationHeader,
            RemoteApiClient client,
            Run<?, ?> run,
            String remoteResource) {
        if (completionSignaled.get()) {
            return;
        }

        try {
            RemoteAcquireStatus status = client.getAcquireStatus(remote, authorizationHeader, lockId);
            RemoteAcquireState state = status.getState();
            String statusLockId = status.getLockId();

            if (state != lastState) {
                lastState = state;
                LOGGER.log(
                        Level.FINE,
                        "Remote acquire state update: serverId={0}, lockId={1}, state={2}",
                        new Object[] {serverId, statusLockId, state});
            }

            consecutivePollFailures = 0;
            switch (state) {
                case QUEUED:
                    return;
                case ACQUIRED:
                    if (bodyStarted) {
                        return;
                    }
                    if (statusLockId == null || statusLockId.isEmpty()) {
                        finishFailure(
                                host,
                                new AbortException(
                                        "Remote acquire returned ACQUIRED without lockId for serverId=" + serverId));
                        return;
                    }
                    lockId = statusLockId;
                    bodyStarted = true;
                    RemoteClientRegistry.get().acquired(statusLockId, status.getResourceNames());
                    cancelPollTask();
                    startHeartbeat(remote, authorizationHeader, client);
                    host.runBody(remoteResource, status.getLockEnvVars(), statusLockId);
                    return;
                case SKIPPED:
                    cancelPollTask();
                    completionSignaled.set(true);
                    RemoteClientRegistry.get().forget(lockId);
                    PauseAction.endCurrentPause(host.context().get(FlowNode.class));
                    LockedResourcesBuildAction.addLog(
                            run,
                            Collections.singletonList(remoteResource),
                            "skipped",
                            host.step().toString());
                    host.context().onSuccess(null);
                    return;
                case FAILED:
                case EXPIRED:
                case UNKNOWN:
                    finishFailure(host, new AbortException(buildFailureMessage(status)));
                    return;
                case CANCELLED:
                    // Keep handling CANCELLED for compatibility with remote-side/admin cancellation.
                    finishFailure(
                            host,
                            new InterruptedException(
                                    "Remote acquire was cancelled (serverId=" + serverId + ", lockId=" + lockId + ")"));
                    return;
                default:
                    finishFailure(host, new AbortException(buildFailureMessage(status)));
            }
        } catch (Exception ex) {
            // Distinguish lockId-not-found (server restart) from transient network failure.
            // 404/410 means the server has no record of this lock - irrecoverable.
            if (ex instanceof RemoteApiException) {
                int httpStatus = ((RemoteApiException) ex).getHttpStatus();
                if (httpStatus == 404 || httpStatus == 410) {
                    // The server has no record of this lock. This used to be split on bodyStarted, with the
                    // still-acquiring side reported as LOCK_WAIT_TIMEOUT - but polling stops the instant the
                    // lock is acquired, so the bodyStarted side is unreachable, and a legitimate allocation
                    // timeout arrives as a terminal FAILED state rather than a 404 (the record now lives for
                    // its terminal TTL, and so does a released queued request). Reaching here therefore means
                    // the record is genuinely gone: the server restarted, the record outlived its TTL, or the
                    // lock id is not one this server issued. Reporting that as a timeout would mislabel it.
                    LOGGER.log(
                            Level.WARNING,
                            "Remote lock record not found (HTTP {0}); server may have restarted or the record "
                                    + "expired. serverId={1}, lockId={2}",
                            new Object[] {httpStatus, serverId, lockId});
                    finishFailure(
                            host,
                            new AbortException("Remote lock record not found (HTTP " + httpStatus
                                    + "), server may have restarted or the record expired. " + "serverId=" + serverId
                                    + ", lockId=" + lockId));
                    return;
                }
            }
            // Transient failure - retry up to threshold before failing the job
            consecutivePollFailures++;
            if (consecutivePollFailures >= MAX_CONSECUTIVE_POLL_FAILURES) {
                LOGGER.log(
                        Level.WARNING,
                        "Remote poll failed {0} consecutive times; giving up. serverId={1}, lockId={2}",
                        new Object[] {consecutivePollFailures, serverId, lockId});
                finishFailure(host, ex);
            } else {
                LOGGER.log(
                        Level.WARNING,
                        "Remote poll failure ({0}/{1}); retrying. serverId={2}, lockId={3}: {4}",
                        new Object[] {
                            consecutivePollFailures, MAX_CONSECUTIVE_POLL_FAILURES, serverId, lockId, ex.getMessage()
                        });
            }
        }
    }

    private void startHeartbeat(RemoteConnection remote, String authorizationHeader, RemoteApiClient client) {
        cancelHeartbeatTask();
        heartbeatTask = jenkins.util.Timer.get()
                .scheduleWithFixedDelay(
                        () -> {
                            if (completionSignaled.get()) {
                                return;
                            }
                            String currentLockId = lockId;
                            if (currentLockId == null || currentLockId.isEmpty()) {
                                return;
                            }
                            try {
                                client.heartbeatLease(remote, authorizationHeader, currentLockId);
                            } catch (Exception ex) {
                                // fail-close: server retains the lock; job continues
                                LOGGER.log(
                                        Level.WARNING,
                                        "Remote heartbeat failed (continuing job; server retains lock): "
                                                + "serverId={0}, lockId={1}: {2}",
                                        new Object[] {serverId, currentLockId, ex.getMessage()});
                            }
                        },
                        RemoteClientDefaults.DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
                        RemoteClientDefaults.DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
                        TimeUnit.SECONDS);
    }

    @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION", justification = "Best-effort remote cleanup should not throw")
    private void releaseBestEffort(Host host) {
        String currentLockId = lockId;
        if (currentLockId == null || currentLockId.isEmpty()) {
            return;
        }

        try {
            LockableResourcesManager lrm = LockableResourcesManager.get();
            RemoteConnection remote = RemoteLockRouting.findConnection(lrm, serverId);
            Run<?, ?> run = host.context().get(Run.class);
            String authorizationHeader = RemoteCredentials.basicAuthHeader(remote, run);
            new RemoteApiClient().releaseLease(remote, authorizationHeader, currentLockId);
            LOGGER.log(
                    Level.FINE, "Remote lock released: serverId={0}, lockId={1}", new Object[] {serverId, currentLockId
                    });
        } catch (Exception ex) {
            LOGGER.log(
                    Level.WARNING,
                    "Failed to release remote lock (fail-closed): serverId={0}, lockId={1}, message={2}",
                    new Object[] {serverId, currentLockId, ex.getMessage()});
        }
    }

    private void finishFailure(Host host, Throwable cause) {
        if (!completionSignaled.compareAndSet(false, true)) {
            return;
        }
        RemoteClientRegistry.get().forget(lockId);
        cancelPollTaskAndRetries();
        cancelHeartbeatTask();
        // Fail-closed: do not attempt release on communication/state failures.
        // Remote side should reclaim via heartbeat timeout.
        host.context().onFailure(cause);
    }

    /** Called by the step's body callback when the lock body finishes - cancel heartbeat and release the lease. */
    public void onBodyFinished(Host host) {
        completionSignaled.set(true);
        RemoteClientRegistry.get().forget(lockId);
        cancelHeartbeatTask();
        releaseBestEffort(host);
    }

    /** Aborts an in-flight session (step stopped): cancel timers, best-effort release, fail the context. */
    public void stop(Host host, Throwable cause) {
        RemoteClientRegistry.get().forget(lockId);
        cancelPollTaskAndRetries();
        cancelHeartbeatTask();
        // Unified remote lock cleanup: release if held (no-op when nothing acquired yet).
        releaseBestEffort(host);
        completionSignaled.set(true);
        host.context().onFailure(cause);
    }

    /** Resumes the session after a controller restart (rebuilds the poll loop or fails closed). */
    @SuppressFBWarnings(
            value = "REC_CATCH_EXCEPTION",
            justification = "Resume is best-effort; any failure resuming the poll loop fails the step closed.")
    public void onResume(Host host) {
        if (lockId == null || lockId.isEmpty()) {
            if (acquirePaused) {
                // We were still waiting for a paused server, so no lock exists anywhere: fail closed
                // rather than leave the step waiting for a retry loop that did not survive the restart.
                LOGGER.log(Level.WARNING, "Jenkins restarted while waiting for a paused remote server: {0}", serverId);
                try {
                    host.context()
                            .onFailure(new AbortException("Jenkins restarted while waiting for the remote server to "
                                    + "accept new acquire requests (serverId=" + serverId + ")"));
                } catch (Exception ex) {
                    LOGGER.log(Level.FINE, "Best-effort: could not signal the resume failure", ex);
                }
                return;
            }
            // Nothing was enqueued before the restart - nothing to resume.
            return;
        }
        if (bodyStarted) {
            // Body was executing when Jenkins restarted. The body is interrupted by Jenkins; we
            // best-effort release the remote lock so the server doesn't hold it indefinitely.
            LOGGER.log(
                    Level.WARNING,
                    "Jenkins restarted during remote lock body execution. "
                            + "Releasing remote lock best-effort. serverId={0}, lockId={1}",
                    new Object[] {serverId, lockId});
            releaseBestEffort(host);
            try {
                host.context()
                        .onFailure(new AbortException("Jenkins restarted during remote lock body execution "
                                + "(serverId=" + serverId + ", lockId=" + lockId + "). "
                                + "Remote lock released best-effort."));
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Failed to signal remote body failure after restart", ex);
            }
            return;
        }
        // QUEUED or ACQUIRED state (polling not yet complete): resume polling
        LockableResourcesManager lrm = LockableResourcesManager.get();
        try {
            RemoteConnection remote = RemoteLockRouting.findConnection(lrm, serverId);
            Run<?, ?> run = host.context().get(Run.class);
            String authorizationHeader = RemoteCredentials.basicAuthHeader(remote, run);
            RemoteApiClient client = new RemoteApiClient();
            // Before the registry existed there was nothing to describe a resumed lock with, so the
            // lock id stood in for the request. requestDescription survives the restart with the session.
            String displayTarget = requestDescription != null ? requestDescription : lockId;
            Run<?, ?> resumedBuild = host.context().get(Run.class);
            RemoteClientRegistry.get().queued(lockId, serverId, displayTarget, resumedBuild);
            if (bodyStarted) {
                RemoteClientRegistry.get().acquired(lockId, null);
            }
            // Restart is not a poll failure: start the post-restart retry budget fresh so a
            // long pre-restart QUEUED period does not shrink it.
            consecutivePollFailures = 0;
            LOGGER.log(
                    Level.INFO,
                    "Resuming remote lock polling after restart: serverId={0}, lockId={1}",
                    new Object[] {serverId, lockId});
            startPolling(host, remote, authorizationHeader, client, run, displayTarget);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Cannot resume remote polling after restart", ex);
            try {
                host.context().onFailure(ex);
            } catch (Exception signalEx) {
                LOGGER.log(Level.FINE, "Best-effort: could not signal resume failure to the step context", signalEx);
            }
        }
    }

    private void cancelPollTaskAndRetries() {
        cancelPollTask();
        cancelAcquireRetryTask();
    }

    private void cancelPollTask() {
        ScheduledFuture<?> task = pollTask;
        if (task != null) {
            task.cancel(false);
            pollTask = null;
        }
    }

    private void cancelHeartbeatTask() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
            heartbeatTask = null;
        }
    }

    private String buildFailureMessage(RemoteAcquireStatus status) {
        String message = status.getMessage();
        String errorCode = status.getErrorCode();
        return "Remote acquire failed (serverId="
                + serverId
                + ", lockId="
                + lockId
                + ", state="
                + status.getState()
                + ", errorCode="
                + errorCode
                + ", message="
                + message
                + ")";
    }
}
