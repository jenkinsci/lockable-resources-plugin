/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A remote lock server that answers whatever a test needs it to answer.
 *
 * <p>The client half of a remote lock is the harder half to test. It is a state machine driven by
 * what a server says over time - a request that queues and is later promoted, one that ends in a
 * terminal state, a lease that goes stale, a server that stops answering for a while - and none of
 * that can be provoked from the client side. A real server will not produce those states on demand
 * either; it produces the ones its own state happens to warrant.
 *
 * <p>So the server is scripted instead. Tests say what the sequence of answers is, and then assert
 * what the client did with it. The scripting is deliberately sequence-shaped rather than a set of
 * flags: {@code queueFor(3)} means "the next three status polls report QUEUED, then it is acquired",
 * which is the shape the interesting client paths actually respond to.
 *
 * <p>Fixed to a single lock id, {@code lock-1}. Nothing here needs two in flight at once, and the
 * URL routing is much clearer for it.
 */
public final class RemoteServerFixture {

    public static final String LOCK_ID = "lock-1";

    public final AtomicInteger acquireRequests = new AtomicInteger();
    public final AtomicInteger statusRequests = new AtomicInteger();
    public final AtomicInteger releaseRequests = new AtomicInteger();
    public final AtomicInteger heartbeatRequests = new AtomicInteger();
    public final AtomicInteger resourcesRequests = new AtomicInteger();

    public final AtomicReference<String> lastAcquireBody = new AtomicReference<>();
    public final AtomicReference<String> lastAcquireRawBody = new AtomicReference<>();
    public final AtomicReference<String> lastAuthorizationHeader = new AtomicReference<>();

    private final AtomicInteger pausedAcquires = new AtomicInteger();
    private int acquireResponseStatus = 202;
    private String acquireResponseBody = "{\"lockId\":\"" + LOCK_ID + "\"}";

    /** Polls still to be answered QUEUED before the lock is reported as acquired. */
    private final AtomicInteger queuedPolls = new AtomicInteger();
    /** Status polls still to be answered with {@link #statusFailureStatus}. */
    private final AtomicInteger failingPolls = new AtomicInteger();

    private int statusFailureStatus = 500;
    private String statusFailureBody = "{\"errorCode\":\"BOOM\",\"message\":\"scripted failure\"}";
    private String acquireStatusResponse = "{\"lockId\":\"" + LOCK_ID + "\",\"state\":\"ACQUIRED\"}";

    private int heartbeatStatus = 204;
    private String heartbeatBody = "";

    private int resourcesStatus = 200;
    private String resourcesBody = "{\"acceptNewAcquires\":true,\"resources\":[]}";

    private HttpServer server;

    // -----------------------------------------------------------------------
    // Scripting
    // -----------------------------------------------------------------------

    public void setAcquireResponse(int status, String body) {
        this.acquireResponseStatus = status;
        this.acquireResponseBody = body;
    }

    /** Answers the next {@code n} acquire calls with the maintenance switch's 503. */
    public void pauseNextAcquires(int n) {
        pausedAcquires.set(n);
    }

    public void setAcquireStatusResponse(String response) {
        this.acquireStatusResponse = response;
    }

    /**
     * Reports QUEUED for the next {@code polls} status calls, then whatever the status response is
     * set to - ACQUIRED unless a test says otherwise. This is the promotion path: a request that
     * waits and then gets its resources.
     */
    public void queueFor(int polls) {
        queuedPolls.set(polls);
    }

    /** Ends the request in a terminal state, the way a server reports a timeout or a skip. */
    public void terminal(String state, String errorCode) {
        this.acquireStatusResponse =
                "{\"lockId\":\"" + LOCK_ID + "\",\"state\":\"" + state + "\",\"errorCode\":\"" + errorCode + "\"}";
    }

    /**
     * Answers the next {@code n} status polls with an error, then goes back to normal. Used to walk
     * up to - and over - the client's tolerance for consecutive poll failures.
     */
    public void failNextPolls(int n, int status, String body) {
        failingPolls.set(n);
        this.statusFailureStatus = status;
        this.statusFailureBody = body;
    }

    /** Answers every status poll with an error, with no recovery. */
    public void failAllPolls(int status, String body) {
        failNextPolls(Integer.MAX_VALUE, status, body);
    }

    public void setHeartbeatResponse(int status, String body) {
        this.heartbeatStatus = status;
        this.heartbeatBody = body;
    }

    public void setResourcesResponse(int status, String body) {
        this.resourcesStatus = status;
        this.resourcesBody = body;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lockable-resources/remote/v1/acquire", new AcquireHandler());
        server.createContext("/lockable-resources/remote/v1/acquire/" + LOCK_ID, new AcquireStatusHandler());
        server.createContext("/lockable-resources/remote/v1/lease/" + LOCK_ID + "/release", new ReleaseHandler());
        server.createContext("/lockable-resources/remote/v1/lease/" + LOCK_ID + "/heartbeat", new HeartbeatHandler());
        server.createContext("/lockable-resources/remote/v1/resources", new ResourcesHandler());
        server.start();
    }

    /**
     * Starts again on the port this fixture was already using.
     *
     * <p>A restart test tears the controller down and brings it back, and the remote it resumes
     * against has to still be at the address stored in its configuration - otherwise the test proves
     * only that a client cannot reach a server that moved.
     */
    public void restartOnSamePort() throws IOException {
        int port = server.getAddress().getPort();
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/lockable-resources/remote/v1/acquire", new AcquireHandler());
        server.createContext("/lockable-resources/remote/v1/acquire/" + LOCK_ID, new AcquireStatusHandler());
        server.createContext("/lockable-resources/remote/v1/lease/" + LOCK_ID + "/release", new ReleaseHandler());
        server.createContext("/lockable-resources/remote/v1/lease/" + LOCK_ID + "/heartbeat", new HeartbeatHandler());
        server.createContext("/lockable-resources/remote/v1/resources", new ResourcesHandler());
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // -----------------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------------

    private final class AcquireHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            acquireRequests.incrementAndGet();
            lastAuthorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (pausedAcquires.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                sendJson(
                        exchange,
                        503,
                        "{\"errorCode\":\"ACQUIRES_PAUSED\",\"message\":\"not accepting new acquires\"}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastAcquireRawBody.set(body);
            String resource = extractString(body, "resource");
            lastAcquireBody.set(resource);
            // Auto-generate lockEnvVars in the status response when a variable was asked for, so a
            // test does not have to spell out what the server would have derived anyway.
            String variable = extractString(body, "variable");
            if (variable != null && resource != null && acquireStatusResponse.contains("\"state\":\"ACQUIRED\"")) {
                acquireStatusResponse = "{\"lockId\":\"" + LOCK_ID + "\",\"state\":\"ACQUIRED\","
                        + "\"lockEnvVars\":{"
                        + "\"" + variable + "\":\"" + resource + "\","
                        + "\"" + variable + "0\":\"" + resource + "\""
                        + "}}";
            }
            sendJson(exchange, acquireResponseStatus, acquireResponseBody);
        }
    }

    private final class AcquireStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            statusRequests.incrementAndGet();
            if (failingPolls.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                sendJson(exchange, statusFailureStatus, statusFailureBody);
                return;
            }
            if (queuedPolls.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                sendJson(exchange, 200, "{\"lockId\":\"" + LOCK_ID + "\",\"state\":\"QUEUED\"}");
                return;
            }
            sendJson(exchange, 200, acquireStatusResponse);
        }
    }

    private final class ReleaseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            releaseRequests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }
    }

    private final class HeartbeatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            heartbeatRequests.incrementAndGet();
            if (heartbeatStatus == 204) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            sendJson(exchange, heartbeatStatus, heartbeatBody);
        }
    }

    private final class ResourcesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            resourcesRequests.incrementAndGet();
            sendJson(exchange, resourcesStatus, resourcesBody);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Pulls a string field out of a request body. Enough for what the handlers need to echo back. */
    private static String extractString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int valueStart = start + marker.length();
        int valueEnd = json.indexOf('"', valueStart);
        return valueEnd >= 0 ? json.substring(valueStart, valueEnd) : null;
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
