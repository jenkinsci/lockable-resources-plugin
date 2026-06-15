/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Remote-lock counterpart to {@link org.jenkins.plugins.lockableresources.queue.QueuedContextStruct}.
 *
 * <p>Holds a pending remote acquire request in the LRM queue so that remote locks participate in the same
 * priority-sorted queue as local pipeline {@code lock()} steps. The request's requirements are carried as
 * the same {@link LockableResourcesStruct} list local lock() uses, so queue promotion resolves through the
 * canonical {@code getAvailableResources} path.
 *
 * <p>All access must be under {@code LockableResourcesManager.syncResources}.
 */
@Restricted(NoExternalUse.class)
public final class RemoteQueueEntry {

    @NonNull
    private final RemoteLockRecord record;

    /** Canonical requirements (main + extra), mirroring {@code LockStep.getResources()}. */
    @NonNull
    private final List<LockableResourcesStruct> structs;

    private final int priority;
    private final long timeoutDeadlineMillis;

    /** Resources resolved by the LRM queue scan, ready to be locked on promotion. */
    private transient List<LockableResource> resolved;

    public RemoteQueueEntry(
            @NonNull RemoteLockRecord record,
            @NonNull List<LockableResourcesStruct> structs,
            int priority,
            long timeoutForAllocateResource,
            @NonNull String timeoutUnit) {
        this.record = record;
        this.structs = structs;
        this.priority = priority;
        if (timeoutForAllocateResource > 0) {
            long deadlineMs;
            try {
                TimeUnit tu = TimeUnit.valueOf(timeoutUnit.toUpperCase(java.util.Locale.ENGLISH));
                deadlineMs = System.currentTimeMillis() + tu.toMillis(timeoutForAllocateResource);
            } catch (IllegalArgumentException e) {
                deadlineMs = 0;
            }
            this.timeoutDeadlineMillis = deadlineMs;
        } else {
            this.timeoutDeadlineMillis = 0;
        }
    }

    @NonNull
    public RemoteLockRecord getRecord() {
        return record;
    }

    @NonNull
    public List<LockableResourcesStruct> getStructs() {
        return structs;
    }

    @NonNull
    public String getLockId() {
        return record.getLockId();
    }

    public int getPriority() {
        return priority;
    }

    public long getTimeoutDeadlineMillis() {
        return timeoutDeadlineMillis;
    }

    @CheckForNull
    public List<LockableResource> getResolved() {
        return resolved;
    }

    public void setResolved(@NonNull List<LockableResource> resolved) {
        this.resolved = resolved;
    }

    public boolean isValid() {
        return record.getState() == RemoteLockState.QUEUED;
    }

    public boolean isTimedOut() {
        return timeoutDeadlineMillis > 0 && System.currentTimeMillis() > timeoutDeadlineMillis;
    }

    /**
     * Called when {@code resources} have been allocated. Transitions the record to ACQUIRED and builds
     * {@code lockEnvVars} (including resource-property env vars) via {@link RemoteResolver#remoteLockEnvVars}
     * - the same helper the immediate-acquire path uses, so both paths produce identical env vars
     * (shared with local {@code lock()} through {@code LockStepExecution.buildLockEnvVars}).
     */
    public void onAcquired(@NonNull List<LockableResource> resources) {
        record.markAcquired(
                LockableResourcesManager.getResourcesNames(resources),
                RemoteResolver.remoteLockEnvVars(record.getLockRequest().getVariable(), resources));
    }

    /** Called when the queue timeout expires. Transitions the record to FAILED. */
    public void onTimeout() {
        record.markFailed("LOCK_WAIT_TIMEOUT");
    }

    @NonNull
    public RemoteLockRequest getLockRequest() {
        return record.getLockRequest();
    }

    @Override
    public String toString() {
        return "RemoteQueueEntry{lockId=" + record.getLockId()
                + ", priority=" + priority
                + ", state=" + record.getState() + "}";
    }
}
