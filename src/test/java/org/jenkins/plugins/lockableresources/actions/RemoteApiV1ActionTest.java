package org.jenkins.plugins.lockableresources.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicInteger;
import net.sf.json.JSONObject;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

@WithJenkins
class RemoteApiV1ActionTest {

    @BeforeEach
    void setUp() {
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    }

    @Test
    void remoteApiContract(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        RemoteApiV1Action action = new RemoteApiV1Action();

        manager.setRemoteApiEnabled(false);
        assertJsonError(invokeAcquire(action, jsonBody("resource", "resource-1")), 403, "REMOTE_API_DISABLED");
        assertJsonError(invokeAcquireStatus("lock-1"), 403, "REMOTE_API_DISABLED");
        assertJsonError(invokeHeartbeat("lock-1"), 403, "REMOTE_API_DISABLED");
        assertJsonError(invokeRelease("lock-1"), 403, "REMOTE_API_DISABLED");

        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("");
        manager.createResource("resource-1");
        // M1E: an unexposed/unknown resource is rejected up front with a uniform 404 (admission).
        assertJsonError(invokeAcquire(action, jsonBody("resource", "resource-1")), 404, "UNKNOWN_RESOURCE");

        manager.setExposeLabel("remote-enabled");
        manager.createResourceWithLabel("resource-2", "different-label");
        assertJsonError(invokeAcquire(action, jsonBody("resource", "resource-2")), 404, "UNKNOWN_RESOURCE");

        manager.createResourceWithLabel("resource-3", "remote-enabled");
        assertJsonError(
                invokeAcquire(action, "{\"lockRequest\":{\"resource\":\"resource-3\"},\"heartbeatIntervalSeconds\":0}"),
                400,
                "INVALID_HEARTBEAT_INTERVAL");
        assertJsonError(
                invokeAcquire(
                        action, "{\"lockRequest\":{\"resource\":\"resource-3\"},\"heartbeatIntervalSeconds\":-1}"),
                400,
                "INVALID_HEARTBEAT_INTERVAL");
        assertJsonError(
                invokeAcquire(
                        action, "{\"lockRequest\":{\"resource\":\"resource-3\"},\"heartbeatIntervalSeconds\":\"abc\"}"),
                400,
                "INVALID_HEARTBEAT_INTERVAL");

        ResponseCapture acquire = invokeAcquire(
                action, "{\"lockRequest\":{\"resource\":\"resource-3\"},\"clientId\":\"http://client-a/\"}");
        assertEquals(202, acquire.status());
        JSONObject payload = acquire.json();
        assertEquals("ACQUIRED", payload.getString("state"));
        assertFalse(payload.getString("lockId").isEmpty());

        assertNotNull(new LockableResourcesRootAction().getDynamic("remote"));
    }

    @Test
    void acquireReturns400WhenBothResourceAndLabelAreAbsent(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().setRemoteApiEnabled(true);

        assertJsonError(invokeAcquire(new RemoteApiV1Action(), "{\"lockRequest\":{}}"), 400, "MISSING_TARGET");
    }

    @Test
    void acquireByLabelSucceedsWhenExposed(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");

        ResponseCapture result =
                invokeAcquire(new RemoteApiV1Action(), "{\"lockRequest\":{\"label\":\"hw\",\"quantity\":1}}");
        assertEquals(202, result.status());
        assertEquals("ACQUIRED", result.json().getString("state"));
    }

    @Test
    void acquireStatusIncludesLockEnvVarsWhenVariableIsSet(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");

        ResponseCapture acquire = invokeAcquire(
                new RemoteApiV1Action(), "{\"lockRequest\":{\"resource\":\"board-1\",\"variable\":\"MY_RES\"}}");
        assertEquals(202, acquire.status());
        String lockId = acquire.json().getString("lockId");

        ResponseCapture status = invokeAcquireStatus(lockId);
        assertEquals(200, status.status());
        assertEquals("ACQUIRED", status.json().getString("state"));
        net.sf.json.JSONObject envVars = status.json().optJSONObject("lockEnvVars");
        assertNotNull(envVars);
        assertEquals("board-1", envVars.getString("MY_RES"));
        assertEquals("board-1", envVars.getString("MY_RES0"));
    }

    @Test
    void acquireWithExtraResourceSucceeds(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");
        manager.createResourceWithLabel("board-2", "hw");

        ResponseCapture result = invokeAcquire(
                new RemoteApiV1Action(),
                "{\"lockRequest\":{\"resource\":\"board-1\"," + "\"extra\":[{\"resource\":\"board-2\"}]}}");
        assertEquals(202, result.status());
        assertEquals("ACQUIRED", result.json().getString("state"));
    }

    @Test
    void acquireWithExtraUnexposedResourceReturns404(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");
        manager.createResource("internal-resource");

        // M1E: an unexposed extra selector fails admission → the whole request is rejected (404),
        // uniform with the unknown-resource case; nothing is partially locked.
        assertJsonError(
                invokeAcquire(
                        new RemoteApiV1Action(),
                        "{\"lockRequest\":{\"resource\":\"board-1\","
                                + "\"extra\":[{\"resource\":\"internal-resource\"}]}}"),
                404,
                "UNKNOWN_RESOURCE");
    }

    @Test
    void acquireWithInvalidSelectStrategyReturns400(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");

        // L-4: an unrecognised resourceSelectStrategy is a client error → 400.
        assertJsonError(
                invokeAcquire(
                        new RemoteApiV1Action(),
                        "{\"lockRequest\":{\"resource\":\"board-1\",\"resourceSelectStrategy\":\"BOGUS\"}}"),
                400,
                "INVALID_SELECT_STRATEGY");
    }

    @Test
    void acquireWithOversizedBodyReturns413(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);

        // M1F L-c: a body larger than the cap is rejected with 413 instead of being read unbounded.
        StringBuilder huge = new StringBuilder(RemoteApiV1Action.MAX_BODY_CHARS + 4096);
        huge.append("{\"lockRequest\":{\"resource\":\"board-1\",\"reason\":\"");
        while (huge.length() <= RemoteApiV1Action.MAX_BODY_CHARS) {
            huge.append("xxxxxxxxxxxxxxxx");
        }
        huge.append("\"}}");

        assertJsonError(invokeAcquire(new RemoteApiV1Action(), huge.toString()), 413, "PAYLOAD_TOO_LARGE");
    }

    @Test
    void acquireWithInvalidExtraEntryReturns400(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");

        assertJsonError(
                invokeAcquire(
                        new RemoteApiV1Action(), "{\"lockRequest\":{\"resource\":\"board-1\"," + "\"extra\":[{}]}}"),
                400,
                "INVALID_EXTRA");
    }

    @Test
    void acquireWithExtraLabelEntrySucceedsAndLocksLabelResource(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");
        manager.createResourceWithLabel("gpu-1", "gpu hw");

        // lock(resource: 'board-1', extra: [[label: 'gpu', quantity: 1]])
        ResponseCapture result = invokeAcquire(
                new RemoteApiV1Action(),
                "{\"lockRequest\":{\"resource\":\"board-1\"," + "\"extra\":[{\"label\":\"gpu\",\"quantity\":1}]}}");
        assertEquals(202, result.status());
        assertEquals("ACQUIRED", result.json().getString("state"));
        // The label-based extra resource must actually be locked (M1B dropped it silently).
        assertNotNull(manager.fromName("gpu-1").getRemoteLockedBy());
    }

    @Test
    void acquireByLabelWithoutQuantityLocksAll(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");
        manager.createResourceWithLabel("board-2", "hw");

        // No "quantity" field → must mean ALL (local "0 means all"), not 1.
        ResponseCapture result = invokeAcquire(new RemoteApiV1Action(), "{\"lockRequest\":{\"label\":\"hw\"}}");
        assertEquals(202, result.status());
        assertEquals("ACQUIRED", result.json().getString("state"));
        assertNotNull(manager.fromName("board-1").getRemoteLockedBy());
        assertNotNull(manager.fromName("board-2").getRemoteLockedBy());
    }

    @Test
    void acquireExtraOnlySucceeds(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");

        // lock(extra: [[resource: 'board-1']]) — no main resource/label (local lock() allows this)
        ResponseCapture result =
                invokeAcquire(new RemoteApiV1Action(), "{\"lockRequest\":{\"extra\":[{\"resource\":\"board-1\"}]}}");
        assertEquals(202, result.status());
        assertEquals("ACQUIRED", result.json().getString("state"));
    }

    @Test
    void remoteEndpointsRequireDedicatedPermission(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new org.jvnet.hudson.test.MockAuthorizationStrategy()
                .grant(jenkins.model.Jenkins.READ)
                .everywhere()
                .to("reader")
                .grant(jenkins.model.Jenkins.READ, LockableResourcesRootAction.REMOTE)
                .everywhere()
                .to("remote-client"));

        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("remote-enabled");
        manager.createResourceWithLabel("resource-perm", "remote-enabled");

        RemoteApiV1Action action = new RemoteApiV1Action();

        // Plain READ must NOT be enough for any remote endpoint (review finding 5-1)
        try (hudson.security.ACLContext ignored = hudson.security.ACL.as2(
                hudson.model.User.getById("reader", true).impersonate2())) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    () -> invokeAcquire(action, jsonBody("resource", "resource-perm")));
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    () -> invokeAcquireStatus("any-lock-id"));
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    () -> invokeHeartbeat("any-lock-id"));
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    () -> invokeRelease("any-lock-id"));
        }

        // A user holding the dedicated REMOTE permission can acquire
        try (hudson.security.ACLContext ignored = hudson.security.ACL.as2(
                hudson.model.User.getById("remote-client", true).impersonate2())) {
            ResponseCapture acquire = invokeAcquire(action, jsonBody("resource", "resource-perm"));
            assertEquals(202, acquire.status());
            assertEquals("ACQUIRED", acquire.json().getString("state"));
        }
    }

    private static ResponseCapture invokeAcquire(RemoteApiV1Action action, String body) throws Exception {
        StaplerRequest2 req = mockJsonRequest(body);
        ResponseCapture response = new ResponseCapture();
        new RemoteApiV1Action.AcquireRouter().doIndex(req, response.response());
        return response;
    }

    private static ResponseCapture invokeAcquireStatus(String lockId) throws Exception {
        ResponseCapture response = new ResponseCapture();
        new RemoteApiV1Action.AcquireStatusResource(lockId).doIndex(mock(StaplerRequest2.class), response.response());
        return response;
    }

    private static ResponseCapture invokeHeartbeat(String lockId) throws Exception {
        ResponseCapture response = new ResponseCapture();
        new RemoteApiV1Action.LeaseResource(lockId).doHeartbeat(mock(StaplerRequest2.class), response.response());
        return response;
    }

    private static ResponseCapture invokeRelease(String lockId) throws Exception {
        ResponseCapture response = new ResponseCapture();
        new RemoteApiV1Action.LeaseResource(lockId).doRelease(mock(StaplerRequest2.class), response.response());
        return response;
    }

    private static StaplerRequest2 mockJsonRequest(String body) throws Exception {
        StaplerRequest2 request = mock(StaplerRequest2.class);
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
        return request;
    }

    private static void assertJsonError(ResponseCapture response, int expectedStatus, String expectedCode) {
        assertEquals(expectedStatus, response.status());
        assertEquals(expectedCode, response.json().getString("errorCode"));
    }

    private static String jsonBody(String key, String value) {
        return "{\"lockRequest\":{\"" + key + "\":\"" + value + "\"}}";
    }

    private static final class ResponseCapture {
        private final AtomicInteger status = new AtomicInteger();
        private final StringWriter body = new StringWriter();
        private final StaplerResponse2 response = mock(StaplerResponse2.class);

        private ResponseCapture() throws Exception {
            doAnswer(invocation -> {
                        status.set(invocation.getArgument(0));
                        return null;
                    })
                    .when(response)
                    .setStatus(anyInt());
            when(response.getWriter()).thenReturn(new PrintWriter(body));
        }

        private StaplerResponse2 response() {
            return response;
        }

        private int status() {
            return status.get();
        }

        private JSONObject json() {
            return JSONObject.fromObject(body.toString());
        }
    }
}
