/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.actions;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.ResourceSelectStrategy;
import org.jenkins.plugins.lockableresources.remote.RemoteLockManager;
import org.jenkins.plugins.lockableresources.remote.RemoteLockRecord;
import org.jenkins.plugins.lockableresources.remote.RemoteLockRequest;
import org.jenkins.plugins.lockableresources.remote.RemoteLockState;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

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
                sendJsonError(rsp, 413, "PAYLOAD_TOO_LARGE",
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

            String resource = lockRequestJson.optString("resource", null);
            if (resource != null) resource = resource.trim();
            if (resource != null && resource.isEmpty()) resource = null;

            String label = lockRequestJson.optString("label", null);
            if (label != null) label = label.trim();
            if (label != null && label.isEmpty()) label = null;

            // extra-only requests are valid (local lock() allows them when extra is present),
            // so only reject when there is no main target AND no extra.
            JSONArray extraPeek = lockRequestJson.optJSONArray("extra");
            boolean hasExtra = extraPeek != null && !extraPeek.isEmpty();
            if (resource == null && label == null && !hasExtra) {
                sendJsonError(rsp, 400, "MISSING_TARGET",
                        "lockRequest must contain at least one of: resource, label, extra");
                return;
            }

            // Exposure/existence is enforced by the admission check inside enqueue (validateRemoteSelectors):
            // a selector referencing something this client can't lock (unknown/unexposed) comes back
            // as a terminal UNKNOWN_* record, which we map to HTTP 404 below. This endpoint only parses the
            // request; "all matching visible" resolution stays on the canonical lock() path.

            boolean skipIfLocked = lockRequestJson.optBoolean("skipIfLocked", false);
            // quantity 0 (or absent) means "all matching" for label requests, matching local lock()
            // (LockableResourcesManager "0 means all"); must NOT default to 1.
            int quantity = lockRequestJson.optInt("quantity", 0);
            String variable = lockRequestJson.optString("variable", null);
            if (variable != null && variable.isEmpty()) variable = null;
            boolean inversePrecedence = lockRequestJson.optBoolean("inversePrecedence", false);
            String resourceSelectStrategy = lockRequestJson.optString("resourceSelectStrategy", "SEQUENTIAL");
            try {
                ResourceSelectStrategy.valueOf(resourceSelectStrategy.toUpperCase(Locale.ENGLISH));
            } catch (IllegalArgumentException e) {
                sendJsonError(rsp, 400, "INVALID_SELECT_STRATEGY",
                        "resourceSelectStrategy must be one of " + Arrays.toString(ResourceSelectStrategy.values()));
                return;
            }
            int priority = lockRequestJson.optInt("priority", 0);
            long timeoutForAllocateResource = lockRequestJson.optLong("timeoutForAllocateResource", 0);
            String timeoutUnit = lockRequestJson.optString("timeoutUnit", "MINUTES");
            String reason = lockRequestJson.optString("reason", null);
            if (reason != null && reason.isEmpty()) reason = null;

            // Parse extra resources (optional - additional resources to lock atomically)
            List<RemoteLockRequest.ExtraResource> extra = null;
            JSONArray extraArray = lockRequestJson.optJSONArray("extra");
            if (extraArray != null && !extraArray.isEmpty()) {
                extra = new ArrayList<>(extraArray.size());
                for (int i = 0; i < extraArray.size(); i++) {
                    JSONObject extraEntry = extraArray.getJSONObject(i);
                    String extraResource = extraEntry.optString("resource", null);
                    if (extraResource != null) extraResource = extraResource.trim();
                    if (extraResource != null && extraResource.isEmpty()) extraResource = null;
                    String extraLabel = extraEntry.optString("label", null);
                    if (extraLabel != null) extraLabel = extraLabel.trim();
                    if (extraLabel != null && extraLabel.isEmpty()) extraLabel = null;
                    if (extraResource == null && extraLabel == null) {
                        sendJsonError(rsp, 400, "INVALID_EXTRA",
                                "extra[" + i + "] must contain at least one of: resource, label");
                        return;
                    }
                    // Exposure/existence of this extra selector is checked by admission in enqueue (see above).
                    int extraQuantity = extraEntry.optInt("quantity", 0); // 0/absent = all (label)
                    extra.add(new RemoteLockRequest.ExtraResource(extraResource, extraLabel, extraQuantity));
                }
            }

            // clientId is optional - identifies the calling Jenkins instance (e.g. root URL)
            String clientId = body.optString("clientId", null);
            if (clientId != null) {
                clientId = clientId.trim();
                if (clientId.isEmpty()) {
                    clientId = null;
                }
            }

            // heartbeatIntervalSeconds is optional but must be a positive integer when present
            if (body.containsKey("heartbeatIntervalSeconds")) {
                int hbi;
                try {
                    hbi = body.getInt("heartbeatIntervalSeconds");
                } catch (Exception e) {
                    sendJsonError(rsp, 400, "INVALID_HEARTBEAT_INTERVAL",
                            "heartbeatIntervalSeconds must be a positive integer");
                    return;
                }
                if (hbi <= 0) {
                    sendJsonError(rsp, 400, "INVALID_HEARTBEAT_INTERVAL",
                            "heartbeatIntervalSeconds must be greater than 0");
                    return;
                }
                // issue #1025 phase 1: the server uses its own heartbeat/STALE constant; a valid
                // client-supplied heartbeatIntervalSeconds is accepted but ignored (per-request
                // configurability is out of phase 1 scope).
            }

            RemoteLockRequest lockRequest = new RemoteLockRequest(
                    resource, label, quantity, variable, inversePrecedence, resourceSelectStrategy,
                    skipIfLocked, extra, priority, timeoutForAllocateResource, timeoutUnit, reason);

            RemoteLockRecord record = RemoteLockManager.get().enqueue(lockRequest, clientId);
            String logTarget = resource != null ? resource : "label:" + label;
            LOGGER.fine("POST /acquire target=" + logTarget + " lockId=" + record.getLockId()
                    + " clientId=" + record.getClientId() + " state=" + record.getState());

            // Admission rejected the request - nothing this client can lock (unknown/unexposed).
            // Uniform 404 (errorCode distinguishes resource vs label); existence is not otherwise revealed.
            // Any other terminal FAILED from enqueue must map to a 4xx, never fall through to a
            // 202 success (defensive - MISSING_TARGET is already rejected at the boundary above).
            if (record.getState() == RemoteLockState.FAILED) {
                String ec = record.getErrorCode();
                if ("UNKNOWN_RESOURCE".equals(ec) || "UNKNOWN_LABEL".equals(ec)) {
                    sendJsonError(rsp, 404, ec, "No lockable resource matches the request");
                } else {
                    sendJsonError(rsp, 400, ec != null ? ec : "ACQUIRE_FAILED",
                            "Remote acquire request was rejected");
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

        public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            Jenkins.get().checkPermission(LockableResourcesRootAction.REMOTE);

            if (!LockableResourcesManager.get().isRemoteApiEnabled()) {
                sendJsonError(rsp, 403, "REMOTE_API_DISABLED", "Remote API is not enabled");
                return;
            }

            RemoteLockRecord record = RemoteLockManager.get().find(lockId);
            if (record == null) {
                sendJsonError(rsp, 404, "LOCK_NOT_FOUND", "Lock not found: " + lockId);
                return;
            }
            // Poll liveness: QUEUED records expire when the client stops polling
            RemoteLockManager.get().touchPoll(lockId);

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

            RemoteLockManager.get().release(lockId);  // idempotent
            rsp.setStatus(204);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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

    static void sendJsonError(StaplerResponse2 rsp, int status, String code, String message)
            throws IOException {
        JSONObject err = new JSONObject();
        err.put("errorCode", code);
        err.put("message", message);
        rsp.setStatus(status);
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.getWriter().write(err.toString());
    }
}
