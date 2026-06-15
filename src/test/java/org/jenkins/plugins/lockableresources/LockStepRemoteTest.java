package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LockStepRemoteTest extends LockStepTestBase {

    @Test
    void lockUsesRemoteFlowWhenServerIdIsSpecified(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        try {
            LockableResourcesManager manager = LockableResourcesManager.get();
            manager.setRemotes(List.of(new RemoteConnection("server-a", remote.baseUrl(), "")));
            manager.setClientId("client-jenkins-a");

            WorkflowJob job = j.createProject(WorkflowJob.class, "remote-lock");
            job.setDefinition(new CpsFlowDefinition(
                    """
                    lock(resource: 'remote-resource', serverId: 'server-a', variable: 'LOCK_NAME') {
                        echo "inside ${env.LOCK_NAME}"
                        semaphore 'remote-body'
                    }
                    echo 'remote-finish'
                    """,
                    true));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("remote-body/1", run);

            assertEquals(1, remote.acquireRequests.get());
            assertTrue(remote.statusRequests.get() >= 1);
            assertEquals("remote-resource", remote.lastAcquireBody.get());
            assertTrue(remote.lastAcquireRawBody.get().contains("\"clientId\":\"client-jenkins-a\""));
            assertEquals(0, remote.releaseRequests.get());
            j.assertLogContains("Remote lock acquired on [Resource: remote-resource] (serverId=server-a, lockId=lock-1)", run);
            j.assertLogContains("inside remote-resource", run);

            SemaphoreStep.success("remote-body/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(run));

            assertEquals(1, remote.releaseRequests.get());
            j.assertLogContains("Remote lock released on [Resource: remote-resource] (serverId=server-a, lockId=lock-1)", run);
            j.assertLogContains("remote-finish", run);
            isPaused(run, 1, 0);
        } finally {
            remote.stop();
        }
    }

    @Test
    void lockWithoutServerIdKeepsUsingLocalFlow(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        try {
            LockableResourcesManager manager = LockableResourcesManager.get();
            manager.setRemotes(List.of(new RemoteConnection("server-a", remote.baseUrl(), "")));
            manager.createResource("local-resource");

            WorkflowJob job = j.createProject(WorkflowJob.class, "local-lock");
            job.setDefinition(new CpsFlowDefinition(
                    """
                    lock(resource: 'local-resource', variable: 'LOCK_NAME') {
                        echo "local ${env.LOCK_NAME}"
                        semaphore 'local-body'
                    }
                    echo 'local-finish'
                    """,
                    true));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("local-body/1", run);

            LockableResource resource = manager.fromName("local-resource");
            assertTrue(resource.isLocked());
            assertEquals(0, remote.acquireRequests.get());
            assertEquals(0, remote.statusRequests.get());
            assertEquals(0, remote.releaseRequests.get());
            j.assertLogContains("Lock acquired on [Resource: local-resource]", run);
            j.assertLogContains("local local-resource", run);

            SemaphoreStep.success("local-body/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(run));

            assertFalse(resource.isLocked());
            j.assertLogContains("Lock released on resource [Resource: local-resource]", run);
            j.assertLogContains("local-finish", run);
            isPaused(run, 1, 0);
        } finally {
            remote.stop();
        }
    }

    @Test
    void lockFailsWhenServerIdIsUnknown(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "missing-remote-lock");
        job.setDefinition(new CpsFlowDefinition(
                """
                lock(resource: 'remote-resource', serverId: 'missing-server') {
                    echo 'should-not-run'
                }
                """,
                true));

        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        j.assertBuildStatus(hudson.model.Result.FAILURE, j.waitForCompletion(run));

        j.assertLogContains("Remote connection not found for serverId=missing-server", run);
        j.assertLogNotContains("should-not-run", run);
        isPaused(run, 0, 0);
    }

    @Test
    void lockFailsWhenRemoteAcquireStatusIsFailed(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.setAcquireStatusResponse("{\"lockId\":\"lock-1\",\"state\":\"FAILED\",\"errorCode\":\"REMOTE_DENIED\",\"message\":\"not granted\"}");
        remote.start();
        try {
            LockableResourcesManager manager = LockableResourcesManager.get();
            manager.setRemotes(List.of(new RemoteConnection("server-a", remote.baseUrl(), "")));

            WorkflowJob job = j.createProject(WorkflowJob.class, "remote-lock-failed");
            job.setDefinition(new CpsFlowDefinition(
                    """
                    lock(resource: 'remote-resource', serverId: 'server-a') {
                        echo 'should-not-run'
                    }
                    """,
                    true));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            j.assertBuildStatus(hudson.model.Result.FAILURE, j.waitForCompletion(run));

            assertEquals(1, remote.acquireRequests.get());
            assertTrue(remote.statusRequests.get() >= 1);
            assertEquals(0, remote.releaseRequests.get());
            j.assertLogContains(
                    "Remote acquire failed (serverId=server-a, lockId=lock-1, state=FAILED, errorCode=REMOTE_DENIED, message=not granted)",
                    run);
            j.assertLogNotContains("should-not-run", run);
            isPaused(run, 1, 1);
        } finally {
            remote.stop();
        }
    }

    @Test
    void lockFailsWhenRemoteAcquireStatusIsExpired(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.setAcquireStatusResponse("{\"lockId\":\"lock-1\",\"state\":\"EXPIRED\",\"errorCode\":\"LOCK_TIMEOUT\",\"message\":\"lease expired\"}");
        remote.start();
        try {
            LockableResourcesManager manager = LockableResourcesManager.get();
            manager.setRemotes(List.of(new RemoteConnection("server-a", remote.baseUrl(), "")));

            WorkflowJob job = j.createProject(WorkflowJob.class, "remote-lock-expired");
            job.setDefinition(new CpsFlowDefinition(
                    """
                    lock(resource: 'remote-resource', serverId: 'server-a') {
                        echo 'should-not-run'
                    }
                    """,
                    true));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            j.assertBuildStatus(hudson.model.Result.FAILURE, j.waitForCompletion(run));

            assertEquals(1, remote.acquireRequests.get());
            assertTrue(remote.statusRequests.get() >= 1);
            assertEquals(0, remote.releaseRequests.get());
            j.assertLogContains(
                    "Remote acquire failed (serverId=server-a, lockId=lock-1, state=EXPIRED, errorCode=LOCK_TIMEOUT, message=lease expired)",
                    run);
            j.assertLogNotContains("should-not-run", run);
            isPaused(run, 1, 1);
        } finally {
            remote.stop();
        }
    }

    @Test
    void lockFailsWhenRemoteAcquireCommunicationFails(JenkinsRule j) throws Exception {
        int unusedPort = findUnusedPort();

        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemotes(List.of(new RemoteConnection("server-a", "http://127.0.0.1:" + unusedPort, "")));

        WorkflowJob job = j.createProject(WorkflowJob.class, "remote-lock-communication-failed");
        job.setDefinition(new CpsFlowDefinition(
                """
                lock(resource: 'remote-resource', serverId: 'server-a') {
                    echo 'should-not-run'
                }
                """,
                true));

        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        j.assertBuildStatus(hudson.model.Result.FAILURE, j.waitForCompletion(run));

        j.assertLogContains("Remote API communication failure: POST /acquire", run);
        j.assertLogNotContains("should-not-run", run);
        isPaused(run, 1, 1);
    }

    @Test
    void lockFailsWhenRemoteAcquireReturnsForbidden(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.setAcquireResponse(403, "{\"errorCode\":\"REMOTE_API_DISABLED\",\"message\":\"forbidden\"}");
        remote.start();
        try {
            LockableResourcesManager manager = LockableResourcesManager.get();
            manager.setRemotes(List.of(new RemoteConnection("server-a", remote.baseUrl(), "")));

            WorkflowJob job = j.createProject(WorkflowJob.class, "remote-lock-forbidden");
            job.setDefinition(new CpsFlowDefinition(
                    """
                    lock(resource: 'remote-resource', serverId: 'server-a') {
                        echo 'should-not-run'
                    }
                    """,
                    true));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            j.assertBuildStatus(hudson.model.Result.FAILURE, j.waitForCompletion(run));

            assertEquals(1, remote.acquireRequests.get());
            assertEquals(0, remote.statusRequests.get());
            assertEquals(0, remote.releaseRequests.get());
            j.assertLogContains("Remote API request failed: POST /acquire/ returned HTTP 403", run);
            j.assertLogNotContains("should-not-run", run);
            isPaused(run, 1, 1);
        } finally {
            remote.stop();
        }
    }

    @Test
    void lockUsesBasicAuthorizationWhenCredentialsIdIsConfigured(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        try {
            addUsernamePasswordCredential("remote-creds", "remote-user", "remote-token");

            LockableResourcesManager manager = LockableResourcesManager.get();
            manager.setRemotes(List.of(new RemoteConnection("server-a", remote.baseUrl(), "remote-creds")));

            WorkflowJob job = j.createProject(WorkflowJob.class, "remote-lock-auth");
            job.setDefinition(new CpsFlowDefinition(
                    """
                    lock(resource: 'remote-resource', serverId: 'server-a') {
                        semaphore 'remote-auth-body'
                    }
                    """,
                    true));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("remote-auth-body/1", run);

            assertEquals("Basic cmVtb3RlLXVzZXI6cmVtb3RlLXRva2Vu", remote.lastAuthorizationHeader.get());

            SemaphoreStep.success("remote-auth-body/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(run));
        } finally {
            remote.stop();
        }
    }

    @Test
    void lockFailsFastWhenCredentialsIdIsMissing(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        try {
            LockableResourcesManager manager = LockableResourcesManager.get();
            manager.setRemotes(List.of(new RemoteConnection("server-a", remote.baseUrl(), "missing-creds")));

            WorkflowJob job = j.createProject(WorkflowJob.class, "remote-lock-missing-creds");
            job.setDefinition(new CpsFlowDefinition(
                    """
                    lock(resource: 'remote-resource', serverId: 'server-a') {
                        echo 'should-not-run'
                    }
                    """,
                    true));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            j.assertBuildStatus(hudson.model.Result.FAILURE, j.waitForCompletion(run));

            assertEquals(0, remote.acquireRequests.get());
            j.assertLogContains("Remote credentials not found for serverId=server-a, credentialsId=missing-creds", run);
            j.assertLogNotContains("should-not-run", run);
            isPaused(run, 0, 0);
        } finally {
            remote.stop();
        }
    }

    @Test
    void lockWithLabelSendsLabelInLockRequest(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        try {
            LockableResourcesManager manager = LockableResourcesManager.get();
            manager.setRemotes(List.of(new RemoteConnection("server-a", remote.baseUrl(), "")));

            WorkflowJob job = j.createProject(WorkflowJob.class, "remote-label-lock");
            job.setDefinition(new CpsFlowDefinition(
                    """
                    lock(label: 'board', quantity: 1, serverId: 'server-a') {
                        semaphore 'label-body'
                    }
                    echo 'label-finish'
                    """,
                    true));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("label-body/1", run);

            assertEquals(1, remote.acquireRequests.get());
            assertTrue(remote.lastAcquireRawBody.get().contains("\"label\":\"board\""));
            assertTrue(remote.lastAcquireRawBody.get().contains("\"quantity\":1"));

            SemaphoreStep.success("label-body/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(run));

            assertEquals(1, remote.releaseRequests.get());
            j.assertLogContains("label-finish", run);
        } finally {
            remote.stop();
        }
    }

    @Test
    void lockDelegatesToRemoteWhenForcedServerIdIsSet(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        try {
            LockableResourcesManager manager = LockableResourcesManager.get();
            manager.setRemotes(List.of(new RemoteConnection("server-forced", remote.baseUrl(), "")));
            manager.setForcedServerId("server-forced");

            WorkflowJob job = j.createProject(WorkflowJob.class, "delegated-lock");
            job.setDefinition(new CpsFlowDefinition(
                    """
                    lock(resource: 'remote-resource') {
                        semaphore 'forced-body'
                    }
                    echo 'forced-finish'
                    """,
                    true));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("forced-body/1", run);

            assertEquals(1, remote.acquireRequests.get());
            assertEquals(0, remote.releaseRequests.get());
            j.assertLogContains("Remote lock acquired on [Resource: remote-resource] (serverId=server-forced, lockId=lock-1)", run);

            SemaphoreStep.success("forced-body/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(run));

            assertEquals(1, remote.releaseRequests.get());
            j.assertLogContains("forced-finish", run);
            isPaused(run, 1, 0);
        } finally {
            remote.stop();
        }
    }

    @Test
    void forcedServerIdOverridesDslServerIdWithInfoLog(JenkinsRule j) throws Exception {
        RemoteServerFixture remote = new RemoteServerFixture();
        remote.start();
        try {
            LockableResourcesManager manager = LockableResourcesManager.get();
            manager.setRemotes(List.of(new RemoteConnection("server-forced", remote.baseUrl(), "")));
            manager.setForcedServerId("server-forced");

            WorkflowJob job = j.createProject(WorkflowJob.class, "forced-override-lock");
            job.setDefinition(new CpsFlowDefinition(
                    """
                    lock(resource: 'remote-resource', serverId: 'server-other') {
                        semaphore 'override-body'
                    }
                    echo 'override-finish'
                    """,
                    true));

            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("override-body/1", run);

            assertEquals(1, remote.acquireRequests.get());
            j.assertLogContains("forcedServerId 'server-forced' overrides DSL serverId 'server-other'", run);
            j.assertLogContains("Remote lock acquired on [Resource: remote-resource] (serverId=server-forced, lockId=lock-1)", run);

            SemaphoreStep.success("override-body/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(run));

            assertEquals(1, remote.releaseRequests.get());
            j.assertLogContains("override-finish", run);
            isPaused(run, 1, 0);
        } finally {
            remote.stop();
        }
    }

    private static void addUsernamePasswordCredential(String credentialsId, String username, String password)
            throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL, credentialsId, "remote lock test credential", username, password));
        SystemCredentialsProvider.getInstance().save();
    }

    private static final class RemoteServerFixture {
        private final AtomicInteger acquireRequests = new AtomicInteger();
        private final AtomicInteger statusRequests = new AtomicInteger();
        private final AtomicInteger releaseRequests = new AtomicInteger();
        private final AtomicReference<String> lastAcquireBody = new AtomicReference<>();
        private final AtomicReference<String> lastAcquireRawBody = new AtomicReference<>();
        private final AtomicReference<String> lastAuthorizationHeader = new AtomicReference<>();
        private int acquireResponseStatus = 202;
        private String acquireResponseBody = "{\"lockId\":\"lock-1\"}";
        private String acquireStatusResponse = "{\"lockId\":\"lock-1\",\"state\":\"ACQUIRED\"}";
        private HttpServer server;

        private void setAcquireResponse(int status, String body) {
            this.acquireResponseStatus = status;
            this.acquireResponseBody = body;
        }

        private void setAcquireStatusResponse(String acquireStatusResponse) {
            this.acquireStatusResponse = acquireStatusResponse;
        }

        private void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/lockable-resources/remote/v1/acquire", new AcquireHandler());
            server.createContext("/lockable-resources/remote/v1/acquire/lock-1", new AcquireStatusHandler());
            server.createContext("/lockable-resources/remote/v1/lease/lock-1/release", new NoContentHandler(releaseRequests));
            server.createContext("/lockable-resources/remote/v1/lease/lock-1/heartbeat", new NoContentHandler(new AtomicInteger()));
            server.start();
        }

        private void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private final class AcquireHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                acquireRequests.incrementAndGet();
                lastAuthorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                lastAcquireRawBody.set(body);
                String resource = extractResource(body);
                lastAcquireBody.set(resource);
                // Auto-generate lockEnvVars in status response when variable is specified
                String variable = extractVariable(body);
                if (variable != null && resource != null
                        && acquireStatusResponse.contains("\"state\":\"ACQUIRED\"")) {
                    acquireStatusResponse = "{\"lockId\":\"lock-1\",\"state\":\"ACQUIRED\","
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
                sendJson(exchange, 200, acquireStatusResponse);
            }
        }

        private static final class NoContentHandler implements HttpHandler {
            private final AtomicInteger counter;

            private NoContentHandler(AtomicInteger counter) {
                this.counter = counter;
            }

            @Override
            public void handle(HttpExchange exchange) throws IOException {
                counter.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            }
        }

        private static String extractResource(String json) {
            String marker = "\"resource\":\"";
            int start = json.indexOf(marker);
            if (start < 0) {
                return null;
            }
            int valueStart = start + marker.length();
            int valueEnd = json.indexOf('"', valueStart);
            return valueEnd >= 0 ? json.substring(valueStart, valueEnd) : null;
        }

        private static String extractVariable(String json) {
            String marker = "\"variable\":\"";
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

    private static int findUnusedPort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }
}