/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * What a remote server publishes, as this controller last saw it.
 *
 * <p>A snapshot, not a live view: the remote is the source of truth for its own resources and this is
 * a copy that was accurate when it was fetched. {@link #getFetchedAt()} says when that was, and
 * {@link #getError()} says why a refresh did not happen, so the page can show an honest age rather
 * than implying the numbers are current.
 */
@Restricted(NoExternalUse.class)
public final class RemoteCatalog {

    private final String serverId;
    private final List<Resource> resources;
    private final boolean acceptNewAcquires;
    private final long fetchedAt;

    @CheckForNull
    private final String error;

    RemoteCatalog(
            String serverId,
            List<Resource> resources,
            boolean acceptNewAcquires,
            long fetchedAt,
            @CheckForNull String error) {
        this.serverId = serverId;
        this.resources = resources == null ? Collections.emptyList() : List.copyOf(resources);
        this.acceptNewAcquires = acceptNewAcquires;
        this.fetchedAt = fetchedAt;
        this.error = error;
    }

    public String getServerId() {
        return serverId;
    }

    @NonNull
    public List<Resource> getResources() {
        return resources;
    }

    /** Whether the remote is currently handing out new locks at all. */
    public boolean isAcceptNewAcquires() {
        return acceptNewAcquires;
    }

    /** When this snapshot was taken, or 0 if nothing has ever been fetched. */
    public long getFetchedAt() {
        return fetchedAt;
    }

    /** Why the last refresh failed, or {@code null} if it succeeded. */
    @CheckForNull
    public String getError() {
        return error;
    }

    public boolean isStale(long ttlMillis) {
        return System.currentTimeMillis() - fetchedAt > ttlMillis;
    }

    /** One resource as published by the remote. State is the remote's, at fetch time. */
    @Restricted(NoExternalUse.class)
    public static final class Resource {

        private final String name;
        private final List<String> labels;
        private final String description;
        private final String state;
        private final String heldByKind;
        private final String heldByClientId;
        private final long since;

        Resource(
                String name,
                List<String> labels,
                String description,
                String state,
                String heldByKind,
                String heldByClientId,
                long since) {
            this.name = name;
            this.labels = labels == null ? Collections.emptyList() : List.copyOf(labels);
            this.description = description == null ? "" : description;
            this.state = state == null ? "" : state;
            this.heldByKind = heldByKind == null ? "" : heldByKind;
            this.heldByClientId = heldByClientId == null ? "" : heldByClientId;
            this.since = since;
        }

        public String getName() {
            return name;
        }

        @NonNull
        public List<String> getLabels() {
            return labels;
        }

        public String getDescription() {
            return description;
        }

        public String getState() {
            return state;
        }

        public String getHeldByKind() {
            return heldByKind;
        }

        public String getHeldByClientId() {
            return heldByClientId;
        }

        public long getSince() {
            return since;
        }
    }
}
