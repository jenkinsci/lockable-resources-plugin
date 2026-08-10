/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.RemoteConnection;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class RemoteCatalogCacheTest {

    @Test
    void doesNotContactADisabledConnection(JenkinsRule j) {
        // Disabling a connection means this controller stops talking to it - including for display.
        LockableResourcesManager manager = LockableResourcesManager.get();
        RemoteConnection disabled = new RemoteConnection("server-a", "http://127.0.0.1:1/jenkins", "");
        disabled.setEnabled(false);
        manager.setRemotes(List.of(disabled));

        assertNull(RemoteCatalogCache.get().get("server-a"));
    }

    @Test
    void returnsNothingForAnUnknownServer(JenkinsRule j) {
        LockableResourcesManager.get().setRemotes(List.of());
        assertNull(RemoteCatalogCache.get().get("no-such-server"));
    }

    @Test
    void aFailedFetchKeepsTheViewRatherThanEmptyingIt(JenkinsRule j) {
        // A remote that is down should age the page, not blank it: display is best-effort, unlike
        // acquiring a lock.
        LockableResourcesManager manager = LockableResourcesManager.get();
        RemoteConnection unreachable = new RemoteConnection("server-a", "http://127.0.0.1:1/jenkins", "");
        manager.setRemotes(List.of(unreachable));

        RemoteCatalog failed = RemoteCatalogCache.get().fetch(unreachable);

        assertNotNull(failed.getError(), "the failure is recorded rather than hidden");
        assertEquals(0, failed.getFetchedAt(), "nothing was ever fetched, so there is no age to claim");
        assertTrue(failed.getResources().isEmpty());
        assertTrue(failed.isStale(RemoteCatalogCache.TTL_MILLIS));
    }

    @Test
    void invalidatingDropsEverySnapshot(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        RemoteConnection remote = new RemoteConnection("server-a", "http://127.0.0.1:1/jenkins", "");
        manager.setRemotes(List.of(remote));
        RemoteCatalogCache.get().fetch(remote);

        RemoteCatalogCache.get().invalidateAll();

        // Nothing cached: the next read starts a refresh and reports "not fetched yet".
        assertNull(RemoteCatalogCache.get().get("server-a"));
    }

    @Test
    void aSnapshotAgesOutAfterItsTtl(JenkinsRule j) {
        RemoteCatalog fresh = new RemoteCatalog("server-a", List.of(), true, System.currentTimeMillis(), null);
        assertFalse(fresh.isStale(RemoteCatalogCache.TTL_MILLIS));

        RemoteCatalog old = new RemoteCatalog(
                "server-a", List.of(), true, System.currentTimeMillis() - RemoteCatalogCache.TTL_MILLIS - 1, null);
        assertTrue(old.isStale(RemoteCatalogCache.TTL_MILLIS));
    }
}
