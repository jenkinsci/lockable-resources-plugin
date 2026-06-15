/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.jenkins.plugins.lockableresources.RemoteConnection;
import org.junit.jupiter.api.Test;

class RemoteApiClientTest {

    @Test
    void testAuthorizationHeaderIsSentWhenPresent() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = startServer("/lockable-resources/remote/v1/acquire", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"lockId\":\"lock-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        try {
            RemoteApiClient client = new RemoteApiClient(Duration.ofSeconds(2));
            RemoteConnection remote = new RemoteConnection("server-a", baseUrl(server), "cred-1");

            RemoteLockRequest lockRequest = new RemoteLockRequest(
                    "resource-1", null, 0, null, false, "SEQUENTIAL", false, null, 0, 0, "MINUTES", null);
            String lockId = client.enqueueAcquire(remote, "Bearer token-1", lockRequest, 10, null);

            assertEquals("lock-1", lockId);
            assertEquals("Bearer token-1", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testAuthorizationHeaderIsOmittedWhenBlank() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = startServer("/lockable-resources/remote/v1/acquire/req-1", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"state\":\"QUEUED\",\"lockId\":\"req-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        try {
            RemoteApiClient client = new RemoteApiClient(Duration.ofSeconds(2));
            RemoteConnection remote = new RemoteConnection("server-a", baseUrl(server), "cred-1");

            RemoteAcquireStatus status = client.getAcquireStatus(remote, "  ", "req-1");

            assertEquals(RemoteAcquireState.QUEUED, status.getState());
            String authorizationHeader = authorization.get();
            assertTrue(
                    authorizationHeader == null || authorizationHeader.isBlank(),
                    "Blank authorization input should not produce a non-blank Authorization header");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testEnqueueAcquireWithoutLockIdUsesActualHttpStatus() throws Exception {
        HttpServer server = startServer("/lockable-resources/remote/v1/acquire", 202, "{}");
        try {
            RemoteApiClient client = new RemoteApiClient(Duration.ofSeconds(2));
            RemoteConnection remote = new RemoteConnection("server-a", baseUrl(server), "cred-1");

            try {
                RemoteLockRequest lockRequest = new RemoteLockRequest(
                        "resource-1", null, 0, null, false, "SEQUENTIAL", false, null, 0, 0, "MINUTES", null);
                client.enqueueAcquire(remote, "Basic abc", lockRequest, 10, null);
            } catch (RemoteApiException ex) {
                assertEquals(202, ex.getHttpStatus());
                assertEquals("INVALID_RESPONSE", ex.getRemoteCode());
                return;
            }
            throw new AssertionError("Expected RemoteApiException");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testInvalidJsonReturnsInvalidJsonCode() throws Exception {
        HttpServer server = startServer("/lockable-resources/remote/v1/acquire/req-1", 200, "not-json");
        try {
            RemoteApiClient client = new RemoteApiClient(Duration.ofSeconds(2));
            RemoteConnection remote = new RemoteConnection("server-a", baseUrl(server), "cred-1");

            try {
                client.getAcquireStatus(remote, "Basic abc", "req-1");
            } catch (RemoteApiException ex) {
                assertEquals(200, ex.getHttpStatus());
                assertEquals("INVALID_JSON", ex.getRemoteCode());
                return;
            }
            throw new AssertionError("Expected RemoteApiException");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testEmptyBaseUrlReturnsInvalidConfigurationError() {
        RemoteApiClient client = new RemoteApiClient(Duration.ofSeconds(2));
        RemoteConnection remote = new RemoteConnection("server-a", "", "cred-1");

        try {
            RemoteLockRequest lockRequest = new RemoteLockRequest(
                    "resource-1", null, 0, null, false, "SEQUENTIAL", false, null, 0, 0, "MINUTES", null);
            client.enqueueAcquire(remote, "Basic abc", lockRequest, 10, null);
        } catch (RemoteApiException ex) {
            assertEquals(-1, ex.getHttpStatus());
            assertEquals("INVALID_CONFIGURATION", ex.getRemoteCode());
            return;
        }
        throw new AssertionError("Expected RemoteApiException");
    }

    private static HttpServer startServer(String path, int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, new FixedResponseHandler(status, body));
        server.start();
        return server;
    }

    private static HttpServer startServer(String path, HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, handler);
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static class FixedResponseHandler implements HttpHandler {
        private final int status;
        private final byte[] body;

        private FixedResponseHandler(int status, String body) {
            this.status = status;
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }
}
