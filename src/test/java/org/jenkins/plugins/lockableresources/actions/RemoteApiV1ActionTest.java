package org.jenkins.plugins.lockableresources.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import net.sf.json.JSONObject;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.GET;

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
        LockableResourcesManager.get().setAllowEmptyOrNullValues(false);

        assertJsonError(invokeAcquire(new RemoteApiV1Action(), "{\"lockRequest\":{}}"), 400, "INVALID_REQUEST");
    }

    @Test
    void acquireAcceptsAnEmptyRequestWhenEmptyValuesAreAllowed(JenkinsRule j) throws Exception {
        // The boundary used to reject a target-less request unconditionally, while a local lock() honours
        // allowEmptyOrNullValues - so the same call behaved differently over the bridge.
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setAllowEmptyOrNullValues(true);

        ResponseCapture result = invokeAcquire(new RemoteApiV1Action(), "{\"lockRequest\":{}}");
        assertEquals(202, result.status.get());
    }

    @Test
    void acquireRejectsResourceAndLabelTogether(JenkinsRule j) throws Exception {
        // Canonical lock() refuses this combination; the bridge used to accept it (M1E-2).
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");

        assertJsonError(
                invokeAcquire(new RemoteApiV1Action(), "{\"lockRequest\":{\"resource\":\"board-1\",\"label\":\"hw\"}}"),
                400,
                "INVALID_REQUEST");
    }

    @Test
    void acquireRejectsPriorityCombinedWithInversePrecedence(JenkinsRule j) throws Exception {
        // Canonical lock() refuses this combination too; the bridge used to accept it silently.
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");

        assertJsonError(
                invokeAcquire(
                        new RemoteApiV1Action(),
                        "{\"lockRequest\":{\"resource\":\"board-1\",\"priority\":5,\"inversePrecedence\":true}}"),
                400,
                "INVALID_REQUEST");
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
                "INVALID_REQUEST");
    }

    @Test
    void acquireRejectsFieldValuesItCannotInterpret(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");
        RemoteApiV1Action action = new RemoteApiV1Action();

        // A quantity that is not a number used to read as 0, and 0 on a label means "every match" -
        // so a typo asked for the whole pool instead of the one machine that was meant.
        assertJsonError(
                invokeAcquire(action, "{\"lockRequest\":{\"label\":\"hw\",\"quantity\":\"abc\"}}"),
                400,
                "INVALID_FIELD_VALUE");
        assertJsonError(
                invokeAcquire(action, "{\"lockRequest\":{\"label\":\"hw\",\"quantity\":true}}"),
                400,
                "INVALID_FIELD_VALUE");
        assertJsonError(
                invokeAcquire(action, "{\"lockRequest\":{\"resource\":\"board-1\",\"priority\":\"high\"}}"),
                400,
                "INVALID_FIELD_VALUE");

        // A timeout that cannot be read used to disable the deadline rather than be refused, which
        // turned a bounded wait into an unbounded one with nothing to see from the caller's side.
        assertJsonError(
                invokeAcquire(
                        action, "{\"lockRequest\":{\"resource\":\"board-1\",\"timeoutForAllocateResource\":\"soon\"}}"),
                400,
                "INVALID_FIELD_VALUE");
        // MINUTE for MINUTES: the local step rejects this in setTimeoutUnit, and now so does the wire.
        assertJsonError(
                invokeAcquire(
                        action,
                        "{\"lockRequest\":{\"resource\":\"board-1\",\"timeoutForAllocateResource\":5,\"timeoutUnit\":\"MINUTE\"}}"),
                400,
                "INVALID_FIELD_VALUE");

        // The same rule inside an extra entry, which is parsed separately.
        assertJsonError(
                invokeAcquire(
                        action,
                        "{\"lockRequest\":{\"resource\":\"board-1\",\"extra\":[{\"label\":\"hw\",\"quantity\":\"all\"}]}}"),
                400,
                "INVALID_FIELD_VALUE");
    }

    @Test
    void acquireStillAcceptsTheLooseFormsClientsActuallySend(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");
        manager.createResourceWithLabel("board-2", "hw");
        RemoteApiV1Action action = new RemoteApiV1Action();

        // Strict parsing must not mean brittle parsing. json-lib reads a numeric string as a number
        // and clients in the wild send one, so "1" has to keep working.
        ResponseCapture numericString =
                invokeAcquire(action, "{\"lockRequest\":{\"label\":\"hw\",\"quantity\":\"1\"}}");
        assertEquals(202, numericString.status());
        assertEquals("ACQUIRED", numericString.json().getString("state"));

        // An explicit null is how serialisers spell "not set"; refusing it would break callers over
        // nothing. It means the same as leaving the field out.
        ResponseCapture explicitNulls = invokeAcquire(
                action,
                "{\"lockRequest\":{\"resource\":\"board-2\",\"quantity\":null,\"priority\":null,"
                        + "\"timeoutForAllocateResource\":null,\"timeoutUnit\":null}}");
        assertEquals(202, explicitNulls.status());
        assertEquals("ACQUIRED", explicitNulls.json().getString("state"));

        // A blank unit falls back to the default, as LockStep#setTimeoutUnit does; lower case is
        // accepted and normalised, again matching the local step.
        manager.createResourceWithLabel("board-3", "hw");
        ResponseCapture blankUnit = invokeAcquire(
                action,
                "{\"lockRequest\":{\"resource\":\"board-3\",\"timeoutForAllocateResource\":5,\"timeoutUnit\":\"  \"}}");
        assertEquals(202, blankUnit.status());

        manager.createResourceWithLabel("board-4", "hw");
        ResponseCapture lowerCaseUnit = invokeAcquire(
                action,
                "{\"lockRequest\":{\"resource\":\"board-4\",\"timeoutForAllocateResource\":5,\"timeoutUnit\":\"seconds\"}}");
        assertEquals(202, lowerCaseUnit.status());

        // Zero and negative keep their existing meanings. Both are expressible through a local
        // lock() and mean "no limit" there, so this endpoint is not the place to start refusing them.
        manager.createResourceWithLabel("board-5", "hw");
        ResponseCapture negative = invokeAcquire(
                action,
                "{\"lockRequest\":{\"resource\":\"board-5\",\"quantity\":-1,\"timeoutForAllocateResource\":-5}}");
        assertEquals(202, negative.status());

        // A null selector is no selector. json-lib hands back the string "null" for a JSON null, so
        // this used to go looking for a resource actually named "null" and report it missing - an
        // answer that sends the caller hunting for the wrong problem.
        assertJsonError(invokeAcquire(action, "{\"lockRequest\":{\"resource\":null}}"), 400, "INVALID_REQUEST");

        // Same for variable: an unset one must not export an environment variable called "null".
        manager.createResourceWithLabel("board-6", "hw");
        ResponseCapture nullVariable =
                invokeAcquire(action, "{\"lockRequest\":{\"resource\":\"board-6\",\"variable\":null}}");
        assertEquals(202, nullVariable.status());
        JSONObject status =
                invokeAcquireStatus(nullVariable.json().getString("lockId")).json();
        assertFalse(status.containsKey("lockEnvVars"), "no variable was asked for, so none is exported");
    }

    @Test
    void pausedServerRefusesNewAcquiresButKeepsLeasesWorking(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");
        manager.createResourceWithLabel("board-2", "hw");

        ResponseCapture acquired = invokeAcquire(new RemoteApiV1Action(), jsonBody("resource", "board-1"));
        assertEquals(202, acquired.status.get());
        String lockId =
                net.sf.json.JSONObject.fromObject(acquired.body.toString()).getString("lockId");

        manager.setAcceptNewAcquires(false);

        // New acquires are refused with a "come back later", not an error the client should give up on.
        assertJsonError(
                invokeAcquire(new RemoteApiV1Action(), jsonBody("resource", "board-2")), 503, "ACQUIRES_PAUSED");

        // Everything the lease in flight needs keeps working.
        assertEquals(200, invokeAcquireStatus(lockId).status.get());
        assertEquals(204, invokeHeartbeat(lockId).status.get());
        assertEquals(204, invokeRelease(lockId).status.get());

        manager.setAcceptNewAcquires(true);
        assertEquals(
                202,
                invokeAcquire(new RemoteApiV1Action(), jsonBody("resource", "board-2"))
                        .status
                        .get());
    }

    @Test
    void resourcesListsExposedResourcesWithTheirState(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");
        manager.createResourceWithLabel("board-2", "hw");
        manager.createResourceWithLabel("internal-1", "secret");

        ResponseCapture acquire = invokeAcquire(new RemoteApiV1Action(), jsonBody("resource", "board-1"));
        assertEquals(202, acquire.status.get());

        ResponseCapture result = invokeResources();
        assertEquals(200, result.status.get());
        net.sf.json.JSONObject payload = net.sf.json.JSONObject.fromObject(result.body.toString());
        assertTrue(payload.getBoolean("acceptNewAcquires"));

        net.sf.json.JSONArray resources = payload.getJSONArray("resources");
        assertEquals(2, resources.size(), "only exposed resources are listed");

        net.sf.json.JSONObject held = resources.getJSONObject(0);
        assertEquals("board-1", held.getString("name"));
        assertEquals("LOCKED", held.getString("state"));
        assertEquals("REMOTE_CLIENT", held.getString("heldByKind"));

        assertEquals("FREE", resources.getJSONObject(1).getString("state"));
    }

    @Test
    void resourcesDoesNotLeakHolderDetails(JenkinsRule j) throws Exception {
        // The server's admin exposed resources, not the names of the builds using them, and this list is
        // rendered on a controller whose viewers may have no account here.
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");

        LockableResource resource = manager.fromName("board-1");
        resource.setNote("internal note");
        resource.setLockReason("because of ticket 1234");
        manager.reserve(java.util.List.of(resource), "operator-a");

        String body = invokeResources().body.toString();
        assertFalse(body.contains("internal note"), body);
        assertFalse(body.contains("ticket 1234"), body);
        assertFalse(body.contains("operator-a"), body);
        assertTrue(body.contains("\"heldByKind\":\"ADMIN\""), body);
    }

    @Test
    void resourcesReportsThePausedServerWhileStatesStayTruthful(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");
        manager.setAcceptNewAcquires(false);

        net.sf.json.JSONObject payload =
                net.sf.json.JSONObject.fromObject(invokeResources().body.toString());
        assertFalse(payload.getBoolean("acceptNewAcquires"));
        // A free resource is still reported as free: "you cannot take it right now" is the page's job.
        assertEquals("FREE", payload.getJSONArray("resources").getJSONObject(0).getString("state"));
    }

    @Test
    void resourcesIsRefusedWhileTheRemoteApiIsDisabled(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().setRemoteApiEnabled(false);

        assertJsonError(invokeResources(), 403, "REMOTE_API_DISABLED");
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

    @Test
    void acquireStatusEndpointIsExplicitlyGetAnnotated() throws Exception {
        Method doIndex = RemoteApiV1Action.AcquireStatusResource.class.getDeclaredMethod(
                "doIndex", StaplerRequest2.class, StaplerResponse2.class);
        assertNotNull(doIndex.getAnnotation(GET.class));
    }

    @Test
    void resourcesEndpointIsExplicitlyGetAnnotated() throws Exception {
        // Without a verb annotation Stapler routes any method here, which the Jenkins security scan
        // reports as a CSRF risk. The endpoint is read-only, so GET is the honest restriction.
        Method doIndex = RemoteApiV1Action.ResourcesResource.class.getDeclaredMethod(
                "doIndex", StaplerRequest2.class, StaplerResponse2.class);
        assertNotNull(doIndex.getAnnotation(GET.class));
    }

    private static ResponseCapture invokeAcquire(RemoteApiV1Action action, String body) throws Exception {
        StaplerRequest2 req = mockJsonRequest(body);
        ResponseCapture response = new ResponseCapture();
        new RemoteApiV1Action.AcquireRouter().doIndex(req, response.response());
        return response;
    }

    private static ResponseCapture invokeResources() throws Exception {
        ResponseCapture response = new ResponseCapture();
        new RemoteApiV1Action.ResourcesResource().doIndex(mock(StaplerRequest2.class), response.response());
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
