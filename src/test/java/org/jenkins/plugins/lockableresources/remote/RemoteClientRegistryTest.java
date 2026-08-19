/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class RemoteClientRegistryTest {

    @Test
    void tracksARequestFromQueuedToReleased(JenkinsRule j) {
        RemoteClientRegistry registry = RemoteClientRegistry.get();
        assertTrue(registry.isEmpty());

        registry.queued("lock-1", "server-a", "plc-01", null);

        List<RemoteClientRegistry.Entry> waiting = registry.getAll();
        assertEquals(1, waiting.size());
        assertEquals("QUEUED", waiting.get(0).getState());
        assertEquals("server-a", waiting.get(0).getServerId());
        assertEquals("plc-01", waiting.get(0).getRequest());
        assertTrue(waiting.get(0).getResourceNames().isEmpty());
        assertEquals(waiting.get(0).getEnqueuedAt(), waiting.get(0).getSince());

        registry.acquired("lock-1", List.of("plc-01", "plc-02"));

        RemoteClientRegistry.Entry held = registry.getAll().get(0);
        assertEquals("ACQUIRED", held.getState());
        assertEquals(List.of("plc-01", "plc-02"), held.getResourceNames());
        assertEquals(held.getAcquiredAt(), held.getSince());

        registry.forget("lock-1");
        assertTrue(registry.isEmpty());
    }

    @Test
    void listsHeldLocksBeforeWaitingOnes(JenkinsRule j) {
        // The page answers "what do I hold, and what am I waiting for" in that order.
        RemoteClientRegistry registry = RemoteClientRegistry.get();
        registry.queued("waiting-1", "server-a", "plc-01", null);
        registry.queued("held-1", "server-b", "plc-02", null);
        registry.acquired("held-1", List.of("plc-02"));

        List<RemoteClientRegistry.Entry> all = registry.getAll();
        assertEquals(2, all.size());
        assertEquals("held-1", all.get(0).getLockId());
        assertEquals("waiting-1", all.get(1).getLockId());

        assertEquals(1, registry.forServer("server-a").size());
        assertEquals(1, registry.forServer("server-b").size());
        assertTrue(registry.forServer("server-c").isEmpty());

        registry.forget("waiting-1");
        registry.forget("held-1");
    }

    @Test
    void survivesAnEntryWhoseBuildIsGone(JenkinsRule j) {
        // Entries outlive their builds: the page still has to render, and holding a build alive to
        // keep a name would be worse than copying the name.
        RemoteClientRegistry registry = RemoteClientRegistry.get();
        registry.queued("lock-2", "server-a", "label:plc", null);

        RemoteClientRegistry.Entry entry = registry.getAll().get(0);
        assertNull(entry.getBuild());
        assertEquals("", entry.getBuildName());
        assertFalse(entry.isAcquired());

        registry.forget("lock-2");
    }

    @Test
    void ignoresUpdatesForUnknownLocks(JenkinsRule j) {
        RemoteClientRegistry registry = RemoteClientRegistry.get();
        registry.acquired("never-registered", List.of("plc-01"));
        registry.forget("never-registered");
        registry.forget(null);
        assertTrue(registry.isEmpty());
    }
}
