/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.RemoteConnection;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Short-lived cache of what other servers publish, so the page can show it without waiting on them.
 *
 * <p>Rendering never performs the HTTP call: a page render hands back whatever snapshot exists and,
 * if it has aged past the TTL, asks for a refresh in the background. A remote that is slow or down
 * therefore costs a stale age indicator, not a page that hangs - display is best-effort, unlike lock
 * acquisition, which stays fail-closed.
 *
 * <p>The TTL is deliberately short. The catalog carries live state (free, locked, reserved) and
 * whether the remote is accepting new acquires, so a minute-old copy would be shown as if current.
 */
@Restricted(NoExternalUse.class)
@Extension
public class RemoteCatalogCache {

    private static final Logger LOGGER = Logger.getLogger(RemoteCatalogCache.class.getName());

    /** How long a snapshot is served before a refresh is triggered. */
    static final long TTL_MILLIS = 10_000L;

    private final ConcurrentHashMap<String, RemoteCatalog> catalogs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> refreshing = new ConcurrentHashMap<>();

    public static RemoteCatalogCache get() {
        return Jenkins.get().getExtensionList(RemoteCatalogCache.class).get(0);
    }

    /**
     * The last snapshot for this server, refreshing in the background when it has gone stale.
     *
     * @return {@code null} when nothing has been fetched yet (a refresh has been started), or when the
     *     connection is unknown or switched off.
     */
    @CheckForNull
    public RemoteCatalog get(@NonNull String serverId) {
        RemoteConnection remote =
                LockableResourcesManager.get().getRemotesAsMap().get(serverId);
        if (remote == null || !remote.isEnabled()) {
            // A disabled connection is not contacted at all - that is what disabling it means.
            catalogs.remove(serverId);
            return null;
        }
        RemoteCatalog current = catalogs.get(serverId);
        if (current == null || current.isStale(TTL_MILLIS)) {
            requestRefresh(remote);
        }
        return current;
    }

    /** Fetches now, on the calling thread. Used by tests and by the background refresh. */
    @NonNull
    RemoteCatalog fetch(@NonNull RemoteConnection remote) {
        RemoteCatalog fetched;
        try {
            String authorizationHeader = RemoteCredentials.basicAuthHeader(remote, null);
            fetched = new RemoteApiClient().listResources(remote, authorizationHeader);
        } catch (Exception ex) {
            // Keep whatever we had: an outage should age the view, not empty it.
            RemoteCatalog previous = catalogs.get(remote.getServerId());
            LOGGER.log(Level.FINE, "Could not refresh the remote catalog for {0}: {1}", new Object[] {
                remote.getServerId(), ex.getMessage()
            });
            fetched = new RemoteCatalog(
                    remote.getServerId(),
                    previous == null ? Collections.emptyList() : previous.getResources(),
                    previous != null && previous.isAcceptNewAcquires(),
                    previous == null ? 0 : previous.getFetchedAt(),
                    ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
        catalogs.put(remote.getServerId(), fetched);
        return fetched;
    }

    private void requestRefresh(@NonNull RemoteConnection remote) {
        String serverId = remote.getServerId();
        if (refreshing.putIfAbsent(serverId, Boolean.TRUE) != null) {
            return; // one in flight is enough; page renders are frequent, remotes are not fast
        }
        jenkins.util.Timer.get().submit(() -> {
            try {
                fetch(remote);
            } catch (Exception ex) {
                LOGGER.log(Level.FINE, "Remote catalog refresh failed for " + serverId, ex);
            } finally {
                refreshing.remove(serverId);
            }
        });
    }

    /** Drops every snapshot - used when the remote configuration changes, and by tests. */
    public void invalidateAll() {
        catalogs.clear();
    }
}
