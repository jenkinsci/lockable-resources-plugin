/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jenkins.plugins.lockableresources.RemoteConnection;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Reading another controller's catalogue.
 *
 * <p>This is what the client page renders remote resources from, and it is the one client call whose
 * job is to be tolerant: a page that fails to draw because a remote was slow is worse than a page
 * that draws slightly old information. The parsing has to survive a server that omits fields, and a
 * server that answers with an error has to arrive as an exception the cache can catch and fall back
 * from - not as an empty catalogue that would render as "this server has nothing".
 */
@WithJenkins
class RemoteResourcesClientTest {

    private static RemoteConnection connection(RemoteServerFixture remote) {
        return new RemoteConnection("server-a", remote.baseUrl(), "");
    }

    @Test
    void readsResourcesAndTheAcceptFlag(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        try {
            remote.setResourcesResponse(200, """
                    {"acceptNewAcquires":false,"resources":[
                      {"name":"board-1","labels":["hw","fast"],"description":"a board",
                       "state":"LOCKED","heldByKind":"REMOTE_CLIENT","heldByClientId":"client-b","since":1234},
                      {"name":"board-2","labels":[],"description":"","state":"FREE"}
                    ]}
                    """);

            RemoteCatalog catalog = new RemoteApiClient().listResources(connection(remote), "");

            assertEquals("server-a", catalog.getServerId());
            assertEquals(2, catalog.getResources().size());
            // The switch travels with the list on purpose: split across two calls, a page could show a
            // resource as free while the server had stopped handing out leases.
            assertFalse(catalog.isAcceptNewAcquires(), "the server said it is not accepting new acquires");

            RemoteCatalog.Resource first = catalog.getResources().get(0);
            assertEquals("board-1", first.getName());
            assertEquals(List.of("hw", "fast"), first.getLabels());
            assertEquals("a board", first.getDescription());
            assertEquals("LOCKED", first.getState());
            assertEquals("REMOTE_CLIENT", first.getHeldByKind());
            assertEquals("client-b", first.getHeldByClientId());
            assertEquals(1234L, first.getSince());

            // A free resource carries no holder. Reading those absent fields must not throw.
            RemoteCatalog.Resource second = catalog.getResources().get(1);
            assertEquals("board-2", second.getName());
            assertEquals("FREE", second.getState());
            assertTrue(second.getLabels().isEmpty());
            assertEquals(0L, second.getSince());
        } finally {
            remote.stop();
        }
    }

    @Test
    void toleratesAServerThatOmitsFields(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        try {
            // No acceptNewAcquires, no labels, no state. An older server, or one that grew the field
            // later, must not take the page down.
            remote.setResourcesResponse(200, "{\"resources\":[{\"name\":\"board-1\"}]}");

            RemoteCatalog catalog = new RemoteApiClient().listResources(connection(remote), "");

            assertEquals(1, catalog.getResources().size());
            assertEquals("board-1", catalog.getResources().get(0).getName());
            assertTrue(catalog.getResources().get(0).getLabels().isEmpty());
            // Absent means "yes" here: a server that never heard of the switch is not paused.
            assertTrue(catalog.isAcceptNewAcquires());
        } finally {
            remote.stop();
        }
    }

    @Test
    void anEmptyCatalogueIsNotAnError(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        try {
            remote.setResourcesResponse(200, "{\"acceptNewAcquires\":true,\"resources\":[]}");

            RemoteCatalog catalog = new RemoteApiClient().listResources(connection(remote), "");

            assertNotNull(catalog);
            assertTrue(catalog.getResources().isEmpty(), "a server may legitimately publish nothing");
        } finally {
            remote.stop();
        }
    }

    @Test
    void aRefusedRequestIsRaisedRatherThanReadAsAnEmptyCatalogue(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        try {
            // 403 when the remote API is switched off. Returning an empty catalogue here would render
            // as "this server publishes nothing", which is a different and wrong statement - the cache
            // has to be able to tell the difference and keep showing what it last knew.
            remote.setResourcesResponse(
                    403, "{\"errorCode\":\"REMOTE_API_DISABLED\",\"message\":\"Remote API is not enabled\"}");

            RemoteApiException ex = assertThrows(
                    RemoteApiException.class, () -> new RemoteApiClient().listResources(connection(remote), ""));
            assertEquals(403, ex.getHttpStatus());
        } finally {
            remote.stop();
        }
    }

    @Test
    void aCatalogueFromAServerThatIsGoneIsRaisedToo(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        String url = remote.baseUrl();
        remote.stop();

        // Nothing is listening any more. This is the case the cache's stale fallback exists for.
        RemoteConnection dead = new RemoteConnection("server-a", url, "");
        assertThrows(Exception.class, () -> new RemoteApiClient().listResources(dead, ""));
    }
}
