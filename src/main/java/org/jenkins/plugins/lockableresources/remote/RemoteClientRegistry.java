/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * What this controller currently holds or waits for on other controllers.
 *
 * <p>The client side of a remote lock lives in a {@link RemoteLockSession} per {@code lock()} step,
 * which is enough to run the step but leaves nobody able to answer "what is this controller doing
 * remotely right now?" - the question the lockable resources page exists to answer for local
 * resources. This registry is that answer: the client-side counterpart of the server-side
 * {@link RemoteLockManager}.
 *
 * <p>It is deliberately not persisted. A remote lock does not survive a restart of this controller
 * (the session either resumes polling or fails closed), so a stale entry read from disk could only
 * ever be wrong; {@code RemoteLockSession.onResume} re-registers what genuinely survived.
 */
@Restricted(NoExternalUse.class)
@Extension
public class RemoteClientRegistry {

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public static RemoteClientRegistry get() {
        return Jenkins.get().getExtensionList(RemoteClientRegistry.class).get(0);
    }

    /** Records a request that has been accepted by the remote and is waiting for its resources. */
    public void queued(
            @NonNull String lockId, @NonNull String serverId, @NonNull String request, @CheckForNull Run<?, ?> build) {
        entries.put(lockId, new Entry(lockId, serverId, request, build));
    }

    /** Promotes a waiting entry to held, naming the resources the remote handed over. */
    public void acquired(@NonNull String lockId, @CheckForNull List<String> resourceNames) {
        Entry entry = entries.get(lockId);
        if (entry != null) {
            entry.markAcquired(resourceNames);
        }
    }

    /** Forgets an entry once the lock is released, skipped or failed. */
    public void forget(@CheckForNull String lockId) {
        if (lockId != null) {
            entries.remove(lockId);
        }
    }

    /** Everything this controller holds or waits for, held entries first, then oldest first. */
    @NonNull
    public List<Entry> getAll() {
        List<Entry> all = new ArrayList<>(entries.values());
        all.sort(Comparator.comparing((Entry e) -> e.isAcquired() ? 0 : 1).thenComparingLong(Entry::getEnqueuedAt));
        return Collections.unmodifiableList(all);
    }

    @NonNull
    public Collection<Entry> forServer(@NonNull String serverId) {
        List<Entry> matching = new ArrayList<>();
        for (Entry e : getAll()) {
            if (serverId.equals(e.getServerId())) {
                matching.add(e);
            }
        }
        return matching;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** One remote lock this controller holds or waits for. */
    @Restricted(NoExternalUse.class)
    public static final class Entry {

        private final String lockId;
        private final String serverId;
        private final String request;
        private final long enqueuedAt;
        private volatile long acquiredAt;
        private volatile List<String> resourceNames;

        /**
         * The build is held weakly and its name copied: the page must keep rendering a lock whose build
         * has since been deleted, and must not be the reason a finished build stays in memory.
         */
        private final WeakReference<Run<?, ?>> build;

        private final String buildName;
        private final String buildUrl;

        private Entry(String lockId, String serverId, String request, @CheckForNull Run<?, ?> build) {
            this.lockId = lockId;
            this.serverId = serverId;
            this.request = request;
            this.enqueuedAt = System.currentTimeMillis();
            this.build = new WeakReference<>(build);
            this.buildName = build != null ? build.getFullDisplayName() : "";
            this.buildUrl = build != null ? build.getUrl() : "";
        }

        private void markAcquired(@CheckForNull List<String> names) {
            this.resourceNames = names == null ? Collections.emptyList() : List.copyOf(names);
            this.acquiredAt = System.currentTimeMillis();
        }

        public String getLockId() {
            return lockId;
        }

        public String getServerId() {
            return serverId;
        }

        /** Human-readable description of what was asked for, e.g. {@code plc-01} or {@code label:plc}. */
        public String getRequest() {
            return request;
        }

        public boolean isAcquired() {
            return acquiredAt > 0;
        }

        public String getState() {
            return isAcquired() ? "ACQUIRED" : "QUEUED";
        }

        public long getEnqueuedAt() {
            return enqueuedAt;
        }

        public long getAcquiredAt() {
            return acquiredAt;
        }

        /** Since when this entry has been in its current state. */
        public long getSince() {
            return isAcquired() ? acquiredAt : enqueuedAt;
        }

        @NonNull
        public List<String> getResourceNames() {
            List<String> names = resourceNames;
            return names == null ? Collections.emptyList() : names;
        }

        @CheckForNull
        public Run<?, ?> getBuild() {
            return build.get();
        }

        public String getBuildName() {
            return buildName;
        }

        public String getBuildUrl() {
            return buildUrl;
        }
    }
}
