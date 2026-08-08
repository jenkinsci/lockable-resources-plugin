/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.actions;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.remote.RemoteLockManager;
import org.jenkins.plugins.lockableresources.remote.RemoteLockRecord;
import org.jenkins.plugins.lockableresources.remote.RemoteLockRequest;
import org.jenkins.plugins.lockableresources.remote.RemoteLockState;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.GET;

/**
 * Serves the remote lock REST API under {@code /lockable-resources/remote/v1/}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /acquire} - enqueue an acquire request</li>
 *   <li>{@code GET  /acquire/{lockId}} - poll acquire status</li>
 *   <li>{@code POST /lease/{lockId}/heartbeat} - renew lease</li>
 *   <li>{@code POST /lease/{lockId}/release} - release lock</li>
 * </ul>
 *
 * <p>All endpoints require the dedicated {@link LockableResourcesRootAction#REMOTE}
 * permission (implied by ADMINISTER). Grant it to the machine users whose API
 * tokens remote client controllers use as {@code credentialsId}.
 * If Remote API is disabled (see {@link LockableResourcesManager#isRemoteApiEnabled()})
 * every endpoint returns 403.
 */
@Restricted(NoExternalUse.class)
public class RemoteApiV1Action {

    private static final Logger LOGGER = Logger.getLogger(RemoteApiV1Action.class.getName());

    // -----------------------------------------------------------------------
    // Dynamic routing for /acquire/{lockId} and /lease/{lockId}/*
    // -----------------------------------------------------------------------

    public Object getDynamic(String token) {
        switch (token) {
            case "acquire":
                return new AcquireRouter();
            case "lease":
                return new LeaseRouter();
            default:
                return null;
        }
    }

    // -----------------------------------------------------------------------
    // Routes POST /acquire and GET /acquire/{lockId}
    // -----------------------------------------------------------------------

    public static final class AcquireRouter {
        @RequirePOST
        public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            Jenkins.get().checkPermission(LockableResourcesRootAction.REMOTE);

            LockableResourcesManager lrm = LockableResourcesManager.get();
            if (!lrm.isRemoteApiEnabled()) {
                sendJsonError(rsp, 403, "REMOTE_API_DISABLED", "Remote API is not enabled on this server");
                return;
            }

            JSONObject body;
            try {
                body = parseJsonBody(req);
            } catch (PayloadTooLargeException e) {
                // Bound the request body so an authenticated client cannot OOM the server.
                sendJsonError(
                        rsp,
                        413,
                        "PAYLOAD_TOO_LARGE",
                        "Request body exceeds the maximum allowed size of " + MAX_BODY_CHARS + " characters");
                return;
            } catch (Exception e) {
                sendJsonError(rsp, 400, "INVALID_JSON", "Request body must be valid JSON");
                return;
            }

            JSONObject lockRequestJson = body.optJSONObject("lockRequest");
            if (lockRequestJson == null) {
                sendJsonError(rsp, 400, "MISSING_LOCK_REQUEST", "Field 'lockRequest' is required");
                return;
            }

            String resource = stringField(lockRequestJson, "resource");
            String label = stringField(lockRequestJson, "label");

            // Whether a request without any target is acceptable depends on allowEmptyOrNullValues, and
            // whether the parameters make sense together is lock() semantics - both are canonical rules,
            // checked inside enqueue() rather than re-implemented here (see LockStepResource.validate).
            //
            // Exposure/existence is enforced by the admission check inside enqueue (validateRemoteSelectors):
            // a selector referencing something this client can't lock (unknown/unexposed) comes back
            // as a terminal UNKNOWN_* record, which we map to HTTP 404 below. This endpoint only parses the
            // request; "all matching visible" resolution stays on the canonical lock() path.

            boolean skipIfLocked = lockRequestJson.optBoolean("skipIfLocked", false);
            // quantity 0 (or absent) means "all matching" for label requests, matching local lock()
            // (LockableResourcesManager "0 means all"); must NOT default to 1.
            int quantity;
            int priority;
            long timeoutForAllocateResource;
            String timeoutUnit;
            try {
                quantity = intField(lockRequestJson, "quantity", 0);
                priority = intField(lockRequestJson, "priority", 0);
                timeoutForAllocateResource = longField(lockRequestJson, "timeoutForAllocateResource", 0);
                timeoutUnit = timeUnitField(lockRequestJson);
            } catch (InvalidFieldException e) {
                sendJsonError(rsp, 400, "INVALID_FIELD_VALUE", e.getMessage());
                return;
            }
            String variable = stringField(lockRequestJson, "variable");
            boolean inversePrecedence = lockRequestJson.optBoolean("inversePrecedence", false);
            String resourceSelectStrategy = stringField(lockRequestJson, "resourceSelectStrategy");
            if (resourceSelectStrategy == null) resourceSelectStrategy = "SEQUENTIAL";
            String reason = stringField(lockRequestJson, "reason");

            // Parse extra resources (optional - additional resources to lock atomically)
            List<RemoteLockRequest.ExtraResource> extra = null;
            JSONArray extraArray = lockRequestJson.optJSONArray("extra");
            if (extraArray != null && !extraArray.isEmpty()) {
                extra = new ArrayList<>(extraArray.size());
                for (int i = 0; i < extraArray.size(); i++) {
                    JSONObject extraEntry = extraArray.getJSONObject(i);
                    String extraResource = stringField(extraEntry, "resource");
                    String extraLabel = stringField(extraEntry, "label");
                    if (extraResource == null && extraLabel == null) {
                        sendJsonError(
                                rsp,
                                400,
                                "INVALID_EXTRA",
                                "extra[" + i + "] must contain at least one of: resource, label");
                        return;
                    }
                    // Exposure/existence of this extra selector is checked by admission in enqueue (see above).
                    int extraQuantity; // 0/absent = all (label)
                    try {
                        extraQuantity = intField(extraEntry, "quantity", 0);
                    } catch (InvalidFieldException e) {
                        sendJsonError(rsp, 400, "INVALID_FIELD_VALUE", "extra[" + i + "]: " + e.getMessage());
                        return;
                    }
                    extra.add(new RemoteLockRequest.ExtraResource(extraResource, extraLabel, extraQuantity));
                }
            }

            // clientId is optional - identifies the calling Jenkins instance (e.g. root URL)
            String clientId = stringField(body, "clientId");

            // heartbeatIntervalSeconds is optional but must be a positive integer when present
            if (body.containsKey("heartbeatIntervalSeconds")) {
                int hbi;
                try {
                    hbi = body.getInt("heartbeatIntervalSeconds");
                } catch (Exception e) {
                    sendJsonError(
                            rsp,
                            400,
                            "INVALID_HEARTBEAT_INTERVAL",
                            "heartbeatIntervalSeconds must be a positive integer");
                    return;
                }
                if (hbi <= 0) {
                    sendJsonError(
                            rsp, 400, "INVALID_HEARTBEAT_INTERVAL", "heartbeatIntervalSeconds must be greater than 0");
                    return;
                }
                // issue #1025 phase 1: the server uses its own heartbeat/STALE constant; a valid
                // client-supplied heartbeatIntervalSeconds is accepted but ignored (per-request
                // configurability is out of phase 1 scope).
            }

            RemoteLockRequest lockRequest = new RemoteLockRequest(
                    resource,
                    label,
                    quantity,
                    variable,
                    inversePrecedence,
                    resourceSelectStrategy,
                    skipIfLocked,
                    extra,
                    priority,
                    timeoutForAllocateResource,
                    timeoutUnit,
                    reason);

            RemoteLockRecord record;
            try {
                record = RemoteLockManager.get().enqueue(lockRequest, clientId);
            } catch (IllegalArgumentException ex) {
                // The canonical validator rejected the request (lock() semantics: no target while
                // allowEmptyOrNullValues is off, resource and label together, priority with
                // inversePrecedence, an unknown select strategy). Its message is the same one a local
                // lock() would print, so pass it through rather than inventing a remote-only wording.
                sendJsonError(rsp, 400, "INVALID_REQUEST", ex.getMessage());
                return;
            }
            String logTarget = resource != null ? resource : "label:" + label;
            LOGGER.fine("POST /acquire target=" + logTarget + " lockId=" + record.getLockId() + " clientId="
                    + record.getClientId() + " state=" + record.getState());

            // Admission rejected the request - nothing this client can lock (unknown/unexposed).
            // Uniform 404 (errorCode distinguishes resource vs label); existence is not otherwise revealed.
            // Any other terminal FAILED from enqueue must map to a 4xx, never fall through to a
            // 202 success (defensive - malformed requests are already rejected as INVALID_REQUEST).
            if (record.getState() == RemoteLockState.FAILED) {
                String ec = record.getErrorCode();
                if ("UNKNOWN_RESOURCE".equals(ec) || "UNKNOWN_LABEL".equals(ec)) {
                    sendJsonError(rsp, 404, ec, "No lockable resource matches the request");
                } else {
                    sendJsonError(rsp, 400, ec != null ? ec : "ACQUIRE_FAILED", "Remote acquire request was rejected");
                }
                return;
            }

            JSONObject response = new JSONObject();
            response.put("lockId", record.getLockId());
            response.put("state", record.getState().name());

            rsp.setStatus(202);
            rsp.setContentType("application/json;charset=UTF-8");
            rsp.getWriter().write(response.toString());
        }

        public Object getDynamic(String lockId) {
            return new AcquireStatusResource(lockId);
        }
    }

    /** Serves {@code GET /acquire/{lockId}}. */
    public static final class AcquireStatusResource {

        private final String lockId;

        AcquireStatusResource(String lockId) {
            this.lockId = lockId;
        }

        @GET
        public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            Jenkins.get().checkPermission(LockableResourcesRootAction.REMOTE);

            if (!LockableResourcesManager.get().isRemoteApiEnabled()) {
                sendJsonError(rsp, 403, "REMOTE_API_DISABLED", "Remote API is not enabled");
                return;
            }

            // Pure read: GET /acquire/{lockId} only observes state. QUEUED lifetime is owned entirely by
            // the server-side unified queue (promotion via proceedNextContext, expiry via the
            // RemoteQueueEntry timeout) - polling does not drive or keep alive the queue entry.
            RemoteLockRecord record = RemoteLockManager.get().find(lockId);
            if (record == null) {
                sendJsonError(rsp, 404, "LOCK_NOT_FOUND", "Lock not found: " + lockId);
                return;
            }

            JSONObject response = new JSONObject();
            response.put("lockId", record.getLockId());
            response.put("state", record.getState().name());
            if (record.getErrorCode() != null) {
                response.put("errorCode", record.getErrorCode());
            }
            if (record.getLockEnvVars() != null) {
                JSONObject envVarsJson = new JSONObject();
                envVarsJson.putAll(record.getLockEnvVars());
                response.put("lockEnvVars", envVarsJson);
            }

            rsp.setStatus(200);
            rsp.setContentType("application/json;charset=UTF-8");
            rsp.getWriter().write(response.toString());
        }
    }

    // -----------------------------------------------------------------------
    // Routes /lease/{lockId}/heartbeat and /lease/{lockId}/release
    // -----------------------------------------------------------------------

    public static final class LeaseRouter {
        public Object getDynamic(String lockId) {
            return new LeaseResource(lockId);
        }
    }

    /** Serves {@code POST /lease/{lockId}/heartbeat} and {@code POST /lease/{lockId}/release}. */
    public static final class LeaseResource {

        private final String lockId;

        LeaseResource(String lockId) {
            this.lockId = lockId;
        }

        @RequirePOST
        public void doHeartbeat(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            Jenkins.get().checkPermission(LockableResourcesRootAction.REMOTE);

            if (!LockableResourcesManager.get().isRemoteApiEnabled()) {
                sendJsonError(rsp, 403, "REMOTE_API_DISABLED", "Remote API is not enabled");
                return;
            }

            boolean ok = RemoteLockManager.get().heartbeat(lockId);
            if (!ok) {
                // Record gone, not ACQUIRED (STALE/QUEUED), or never existed
                RemoteLockRecord record = RemoteLockManager.get().find(lockId);
                if (record != null && record.getState() == RemoteLockState.STALE) {
                    sendJsonError(rsp, 410, "LOCK_STALE", "Lock is STALE; contact administrator: " + lockId);
                } else {
                    sendJsonError(rsp, 410, "LOCK_NOT_FOUND", "Lock not found or not active: " + lockId);
                }
                return;
            }

            rsp.setStatus(204);
        }

        @RequirePOST
        public void doRelease(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            Jenkins.get().checkPermission(LockableResourcesRootAction.REMOTE);

            if (!LockableResourcesManager.get().isRemoteApiEnabled()) {
                sendJsonError(rsp, 403, "REMOTE_API_DISABLED", "Remote API is not enabled");
                return;
            }

            RemoteLockManager.get().release(lockId); // idempotent
            rsp.setStatus(204);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Typed field parsing
    //
    // The lock() DSL gets its types from Java: a pipeline cannot pass "abc" where an int is declared,
    // and LockStep#setTimeoutUnit rejects a unit that is not a TimeUnit. JSON has no such guarantee,
    // and reading these fields with optInt/optLong/optString means an uninterpretable value silently
    // becomes the default instead of being refused. That is not a smaller version of the same
    // behaviour - it changes what the request means, in the direction of doing more:
    //
    //   * quantity that is not a number becomes 0, and 0 on a label means "every match", so a typo
    //     asks for the whole pool rather than the one machine that was meant;
    //   * timeoutForAllocateResource that is not a number becomes 0, and a timeoutUnit that is not a
    //     TimeUnit disables the deadline outright, so a bounded wait silently becomes unbounded.
    //
    // Neither is reachable through a local lock(), so both arrived with this endpoint. Parse strictly
    // instead, and let the caller hear about it.
    //
    // Strict does not mean brittle. A numeric string ("2") still parses, because json-lib accepts it
    // and clients in the wild send it; an explicit null is treated as absent, because serialisers
    // routinely emit null for an unset field and refusing those would break callers over nothing.
    // -----------------------------------------------------------------------

    /** Signals a field whose value cannot be interpreted as its type; mapped to HTTP 400. */
    private static final class InvalidFieldException extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidFieldException(String message) {
            super(message);
        }
    }

    /** True when the key is absent or explicitly null - both mean "not supplied". */
    private static boolean isAbsent(JSONObject json, String key) {
        return !json.containsKey(key) || JSONNull.getInstance().equals(json.get(key));
    }

    /**
     * A string field, where absent, explicitly null, and blank all mean "not supplied".
     *
     * <p>The null case is why this exists rather than a bare optString: json-lib hands back the
     * four-character string {@code "null"} for a JSON null, so {@code "resource": null} would ask for
     * a resource actually named "null", and {@code "variable": null} would export an environment
     * variable by that name.
     */
    @edu.umd.cs.findbugs.annotations.CheckForNull
    private static String stringField(JSONObject json, String key) {
        if (isAbsent(json, key)) {
            return null;
        }
        String value = json.optString(key, null);
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private static int intField(JSONObject json, String key, int defaultValue) throws InvalidFieldException {
        if (isAbsent(json, key)) {
            return defaultValue;
        }
        try {
            return json.getInt(key);
        } catch (RuntimeException e) {
            throw new InvalidFieldException(key + " must be an integer, got: " + json.get(key));
        }
    }

    private static long longField(JSONObject json, String key, long defaultValue) throws InvalidFieldException {
        if (isAbsent(json, key)) {
            return defaultValue;
        }
        try {
            return json.getLong(key);
        } catch (RuntimeException e) {
            throw new InvalidFieldException(key + " must be a number, got: " + json.get(key));
        }
    }

    /**
     * Reads {@code timeoutUnit} the way {@link org.jenkins.plugins.lockableresources.LockStep#setTimeoutUnit}
     * does: blank falls back to the default, anything else must name a {@link TimeUnit} and is upper-cased.
     */
    private static String timeUnitField(JSONObject json) throws InvalidFieldException {
        String value = stringField(json, "timeoutUnit");
        if (value == null) {
            return "MINUTES";
        }
        String normalized = value.toUpperCase(Locale.ENGLISH);
        try {
            TimeUnit.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new InvalidFieldException("Invalid timeoutUnit: " + value);
        }
        return normalized;
    }

    /** Cap on the POST body size (in characters) to avoid unbounded reads. */
    static final int MAX_BODY_CHARS = 1024 * 1024; // 1 MiB

    /** Signals that the request body exceeded {@link #MAX_BODY_CHARS}; mapped to HTTP 413. */
    private static final class PayloadTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static JSONObject parseJsonBody(StaplerRequest2 req) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            char[] buf = new char[1024];
            int n;
            int total = 0;
            while ((n = reader.read(buf)) != -1) {
                total += n;
                if (total > MAX_BODY_CHARS) {
                    throw new PayloadTooLargeException();
                }
                sb.append(buf, 0, n);
            }
        }
        return JSONObject.fromObject(sb.toString());
    }

    static void sendJsonError(StaplerResponse2 rsp, int status, String code, String message) throws IOException {
        JSONObject err = new JSONObject();
        err.put("errorCode", code);
        err.put("message", message);
        rsp.setStatus(status);
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.getWriter().write(err.toString());
    }
}
