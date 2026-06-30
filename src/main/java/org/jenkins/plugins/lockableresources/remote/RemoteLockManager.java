/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.PeriodicWork;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Server-side manager for remote lock records.
 *
 * <p>Runs a 1-second tick loop (via {@link PeriodicWork}) that:
 * <ul>
 *   <li>Attempts to promote QUEUED records to ACQUIRED when their resource(s) become free.</li>
 *   <li>Marks ACQUIRED records as STALE when heartbeat times out.</li>
 *   <li>Cleans up terminal (SKIPPED/FAILED) records after a TTL.</li>
 * </ul>
 *
 * <p>STALE locks are held until an administrator explicitly releases them.
 * In-memory state is lost on Jenkins restart; all transient remote locks are
 * automatically freed because {@link LockableResource#remoteLockedBy} is transient.
 */
@Restricted(NoExternalUse.class)
@Extension
public class RemoteLockManager extends PeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(RemoteLockManager.class.getName());

    /**
     * Time after which an ACQUIRED record with no heartbeat is marked STALE.
     * = max(heartbeatInterval x 6, 60) seconds.
     */
    static final long STALE_THRESHOLD_MS =
            TimeUnit.SECONDS.toMillis(Math.max(RemoteClientDefaults.DEFAULT_HEARTBEAT_INTERVAL_SECONDS * 6, 60));

    /** Terminal records (SKIPPED/FAILED) are removed from the map after this TTL. */
    private static final long TERMINAL_TTL_MS = TimeUnit.SECONDS.toMillis(120);

    private final ConcurrentHashMap<String, RemoteLockRecord> records = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    public static RemoteLockManager get() {
        return Jenkins.get().getExtensionList(RemoteLockManager.class).get(0);
    }

    // -----------------------------------------------------------------------
    @Override
    public long getRecurrencePeriod() {
        return 1000L; // 1 second
    }

    // -----------------------------------------------------------------------
    @Override
    protected void doRun() {
        // Queue promotion is now handled by LRM.proceedNextContext() via the unified queue bridge.
        // Only periodic stale detection and terminal-record TTL cleanup remain here.
        maybeScanStale();
    }

    // -----------------------------------------------------------------------
    /**
     * Enqueues a new remote acquire request.
     *
     * <p>Immediately attempts to lock the resource(s). If successful the returned record
     * has state {@link RemoteLockState#ACQUIRED}. If the resource is busy and
     * {@code skipIfLocked} is true, state is {@link RemoteLockState#SKIPPED}.
     * Otherwise state is {@link RemoteLockState#QUEUED} and the entry is registered in the LRM
     * queue so it participates in unified priority dispatch with local pipeline lock() steps.
     */
    public RemoteLockRecord enqueue(@NonNull RemoteLockRequest lockRequest, @CheckForNull String clientId) {
        String lockId = UUID.randomUUID().toString();
        RemoteLockRecord record = new RemoteLockRecord(lockId, lockRequest, clientId);

        LockableResourcesManager lrm = LockableResourcesManager.get();
        RemoteResolver resolver = new RemoteResolver(lrm);
        synchronized (LockableResourcesManager.syncResources) {
            // Admission: a selector referencing something this client can't lock (unknown/unexposed) is
            // rejected up front (terminal; the caller maps UNKNOWN_* to HTTP 404). This avoids creating
            // ephemeral resources and avoids queueing forever for something that will never be lockable.
            String errorCode = resolver.validateRemoteSelectors(lockRequest);
            if (errorCode != null) {
                record.markFailed(errorCode);
                records.put(lockId, record);
                LOGGER.fine("Remote acquire rejected: lockId=" + lockId + " errorCode=" + errorCode);
                return record;
            }
            // Resolve through the SAME canonical path local lock() uses (no re-implementation of lock()
            // semantics), with the exposeLabel set as candidate filter. extra / label / quantity(0=all) /
            // resourceSelectStrategy / property env vars all come from the canonical path. A request whose
            // (exposed, existing) targets are merely busy stays QUEUED, exactly like local.
            List<LockableResourcesStruct> structs = resolver.toRemoteStructs(lockRequest);
            if (structs.isEmpty()) {
                record.markFailed("MISSING_TARGET");
            } else {
                List<LockableResource> available = resolver.availableForRemote(structs, lockRequest);
                if (available != null && !available.isEmpty()) {
                    lrm.lockForRemote(available, lockId);
                    record.markAcquired(
                            LockableResourcesManager.getResourcesNames(available),
                            RemoteResolver.remoteLockEnvVars(lockRequest.getVariable(), available));
                } else if (lockRequest.isSkipIfLocked()) {
                    record.markSkipped();
                } else {
                    // Busy - register in the LRM unified queue for priority dispatch with local lock() steps.
                    RemoteQueueEntry entry = new RemoteQueueEntry(
                            record,
                            structs,
                            lockRequest.getPriority(),
                            lockRequest.getTimeoutForAllocateResource(),
                            lockRequest.getTimeoutUnit());
                    lrm.queueRemote(entry);
                }
            }
        }

        records.put(lockId, record);
        LOGGER.fine(
                "Remote acquire enqueued: lockId=" + lockId + " state=" + record.getState() + " clientId=" + clientId);
        return record;
    }

    // -----------------------------------------------------------------------
    /**
     * Returns the record for the given lockId, or {@code null} if not found.
     */
    @CheckForNull
    public RemoteLockRecord find(String lockId) {
        return records.get(lockId);
    }

    // -----------------------------------------------------------------------
    /**
     * Updates the heartbeat timestamp for an ACQUIRED lock.
     *
     * @return {@code false} if the lockId is unknown or not in ACQUIRED state.
     */
    public boolean heartbeat(String lockId) {
        RemoteLockRecord record = records.get(lockId);
        if (record == null) {
            return false;
        }
        if (record.getState() != RemoteLockState.ACQUIRED) {
            return false;
        }
        record.heartbeat();
        return true;
    }

    // -----------------------------------------------------------------------
    /**
     * Releases a lock, freeing the underlying resource(s). Idempotent.
     */
    public void release(String lockId) {
        RemoteLockRecord record = records.remove(lockId);
        if (record == null) {
            return;
        }
        LockableResourcesManager lrm = LockableResourcesManager.get();

        // Decide and mutate queue state atomically under syncResources so a concurrent queue
        // promotion (proceedNextContext -> proceedRemoteEntry, which checks entry.isValid() ==
        // QUEUED) cannot acquire resources for an already-released record. Without this guard a
        // QUEUED record released at the same instant its resources free up would leave an orphan
        // remote lock (resource pinned, record gone from the map, unrecoverable until restart).
        List<String> namesToUnlock = null;
        synchronized (LockableResourcesManager.syncResources) {
            RemoteLockState state = record.getState();
            if (state == RemoteLockState.ACQUIRED || state == RemoteLockState.STALE) {
                List<String> names = record.getAcquiredResourceNames();
                if (names != null && !names.isEmpty()) {
                    namesToUnlock = names;
                }
            } else if (state == RemoteLockState.QUEUED) {
                // Terminal-mark before unqueue so a not-yet-run promotion is excluded.
                record.markFailed("RELEASED");
                lrm.unqueueRemote(lockId);
            }
        }

        // unlockRemoteResources() / scheduleQueueMaintenance() must run OUTSIDE syncResources
        // (they touch the Jenkins Queue lock).
        if (namesToUnlock != null) {
            // frees resources, wakes local+remote waiters, and schedules maintenance
            lrm.unlockRemoteResources(namesToUnlock, lockId);
        } else {
            lrm.scheduleQueueMaintenance();
        }
        LOGGER.fine("Remote lock released: lockId=" + lockId + " resources=" + record.getAcquiredResourceNames());
    }

    // -----------------------------------------------------------------------
    /**
     * Marks ACQUIRED records STALE when heartbeat has been missing too long,
     * and cleans up terminal records (SKIPPED/FAILED) after their TTL.
     * QUEUED records are expired by the unified queue's own timeout
     * ({@code RemoteQueueEntry} deadline = {@code timeoutForAllocateResource}), not here.
     */
    private void maybeScanStale() {
        long now = System.currentTimeMillis();
        for (RemoteLockRecord record : records.values()) {
            RemoteLockState state = record.getState();
            if (state == RemoteLockState.ACQUIRED) {
                if (now - record.getLastHeartbeatAt() > STALE_THRESHOLD_MS) {
                    record.markStale();
                    LOGGER.log(
                            Level.WARNING,
                            "Remote lock STALE: lockId={0} resources={1} lastHeartbeatAge={2}ms",
                            new Object[] {
                                record.getLockId(), record.getAcquiredResourceNames(), now - record.getLastHeartbeatAt()
                            });
                }
            } else if (state == RemoteLockState.SKIPPED || state == RemoteLockState.FAILED) {
                // Measure retention from when the record became terminal, NOT from enqueue: a record that
                // times out after a long queue wait (timeoutForAllocateResource > TERMINAL_TTL_MS) only
                // becomes FAILED at the deadline, so an enqueue-based TTL would already be exceeded at that
                // instant and the record would be evicted before a polling client can read the terminal
                // state - surfacing a legitimate LOCK_WAIT_TIMEOUT as a 404. Terminal-time TTL keeps the
                // FAILED/SKIPPED record observable for the full TTL regardless of how long the wait was.
                if (now - record.getTerminalAt() > TERMINAL_TTL_MS) {
                    records.remove(record.getLockId());
                }
            }
        }
    }
}
