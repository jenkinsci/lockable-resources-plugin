/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Server-side record tracking a remote lock request lifecycle.
 *
 * <p>All state transitions are performed by {@link RemoteLockManager}'s single-threaded
 * tick executor, except for {@link #heartbeat()} which is written by the HTTP thread.
 * Fields are {@code volatile} to ensure visibility across threads.
 */
@Restricted(NoExternalUse.class)
public final class RemoteLockRecord {

    private final String lockId;

    @NonNull
    private final RemoteLockRequest lockRequest;
    /** Caller-supplied client identifier (e.g. the A-side Jenkins root URL). May be null if not provided. */
    @CheckForNull
    private final String clientId;

    private volatile RemoteLockState state;
    private final long enqueuedAt;
    private volatile long acquiredAt;
    private volatile long lastHeartbeatAt;

    /**
     * Timestamp when the record entered a terminal state (SKIPPED/FAILED); 0 while non-terminal.
     * The terminal-record TTL cleanup measures retention from this instant, not from {@link #enqueuedAt},
     * so a record that becomes terminal only after a long queue wait (e.g. timeoutForAllocateResource
     * &gt; the TTL) is still retained for the full TTL and can be observed by a polling client.
     */
    private volatile long terminalAt;

    @CheckForNull
    private volatile String errorCode;

    @CheckForNull
    private volatile List<String> acquiredResourceNames;

    @CheckForNull
    private volatile Map<String, String> lockEnvVars;

    RemoteLockRecord(@NonNull String lockId, @NonNull RemoteLockRequest lockRequest, @CheckForNull String clientId) {
        this.lockId = lockId;
        this.lockRequest = lockRequest;
        this.clientId = clientId;
        this.state = RemoteLockState.QUEUED;
        long now = System.currentTimeMillis();
        this.enqueuedAt = now;
        this.lastHeartbeatAt = now;
    }

    public String getLockId() {
        return lockId;
    }

    @NonNull
    public RemoteLockRequest getLockRequest() {
        return lockRequest;
    }

    /**
     * Returns the first acquired resource name, or the requested resource name if not yet acquired.
     * Returns {@code null} for label-only requests that have not yet been acquired.
     */
    @CheckForNull
    public String getResourceName() {
        List<String> names = acquiredResourceNames;
        if (names != null && !names.isEmpty()) {
            return names.get(0);
        }
        return lockRequest.getResource();
    }

    @CheckForNull
    public List<String> getAcquiredResourceNames() {
        return acquiredResourceNames;
    }

    @CheckForNull
    public Map<String, String> getLockEnvVars() {
        return lockEnvVars;
    }

    @CheckForNull
    public String getClientId() {
        return clientId;
    }

    public RemoteLockState getState() {
        return state;
    }

    public long getEnqueuedAt() {
        return enqueuedAt;
    }

    /** Instant the record became terminal (SKIPPED/FAILED), or 0 if still non-terminal. */
    public long getTerminalAt() {
        return terminalAt;
    }

    public long getAcquiredAt() {
        return acquiredAt;
    }

    public long getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    @CheckForNull
    public String getErrorCode() {
        return errorCode;
    }

    void markAcquired(@NonNull List<String> names, @CheckForNull Map<String, String> lockEnvVars) {
        this.acquiredResourceNames = Collections.unmodifiableList(new ArrayList<>(names));
        this.lockEnvVars = lockEnvVars;
        long now = System.currentTimeMillis();
        this.acquiredAt = now;
        this.lastHeartbeatAt = now;
        this.state = RemoteLockState.ACQUIRED;
    }

    void markSkipped() {
        this.terminalAt = System.currentTimeMillis();
        this.state = RemoteLockState.SKIPPED;
    }

    void markFailed(String code) {
        this.errorCode = code;
        this.terminalAt = System.currentTimeMillis();
        this.state = RemoteLockState.FAILED;
    }

    void markStale() {
        this.state = RemoteLockState.STALE;
    }

    void heartbeat() {
        this.lastHeartbeatAt = System.currentTimeMillis();
    }
}
