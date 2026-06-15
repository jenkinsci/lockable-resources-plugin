/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkins.plugins.lockableresources.RemoteConnection;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Thin HTTP client for the remote lock API.
 */
@Restricted(NoExternalUse.class)
public class RemoteApiClient {

    private static final Logger LOGGER = Logger.getLogger(RemoteApiClient.class.getName());
    private static final int MAX_LOGGED_BODY_CHARS = 512;

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public RemoteApiClient() {
        this(Duration.ofSeconds(RemoteClientDefaults.DEFAULT_REQUEST_TIMEOUT_SECONDS));
    }

    public RemoteApiClient(@NonNull Duration requestTimeout) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
        this.requestTimeout = requestTimeout;
    }

    /**
     * POST /acquire and return accepted lockId.
     */
    @NonNull
    public String enqueueAcquire(
            @NonNull RemoteConnection remote,
            @NonNull String authorizationHeader,
            @NonNull RemoteLockRequest lockRequest,
            int heartbeatIntervalSeconds,
            @CheckForNull String clientId)
            throws RemoteApiException {
        JSONObject requestBody = new JSONObject();
        requestBody.put("lockRequest", buildLockRequestJson(lockRequest));
        if (heartbeatIntervalSeconds > 0) {
            requestBody.put("heartbeatIntervalSeconds", heartbeatIntervalSeconds);
        }
        if (clientId != null && !clientId.isEmpty()) {
            requestBody.put("clientId", clientId);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(resolve(remote, "/acquire/"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()));

        applyAuthorizationHeader(requestBuilder, authorizationHeader);
        HttpRequest request = requestBuilder.build();

        DecodedJsonResponse decoded = sendAndDecodeJson(remote.getServerId(), "POST", "/acquire/", request);
        JSONObject response = decoded.body;
        String lockId = extractLockId(response, null);
        if (lockId == null || lockId.isEmpty()) {
            throw new RemoteApiException(
                    "Remote acquire response did not contain lockId",
                    decoded.httpStatus,
                    remote.getServerId(),
                    "INVALID_RESPONSE");
        }
        return lockId;
    }

    private JSONObject buildLockRequestJson(RemoteLockRequest lr) {
        JSONObject json = new JSONObject();
        if (lr.getResource() != null) {
            json.put("resource", lr.getResource());
        }
        if (lr.getLabel() != null) {
            json.put("label", lr.getLabel());
        }
        if (lr.getQuantity() > 0) {
            json.put("quantity", lr.getQuantity());
        }
        if (lr.getVariable() != null) {
            json.put("variable", lr.getVariable());
        }
        json.put("inversePrecedence", lr.isInversePrecedence());
        json.put("resourceSelectStrategy", lr.getResourceSelectStrategy());
        json.put("skipIfLocked", lr.isSkipIfLocked());
        if (lr.getExtra() != null && !lr.getExtra().isEmpty()) {
            JSONArray extraJson = new JSONArray();
            for (RemoteLockRequest.ExtraResource r : lr.getExtra()) {
                JSONObject rJson = new JSONObject();
                if (r.getResource() != null) rJson.put("resource", r.getResource());
                if (r.getLabel() != null) rJson.put("label", r.getLabel());
                if (r.getQuantity() > 0) rJson.put("quantity", r.getQuantity());
                extraJson.add(rJson);
            }
            json.put("extra", extraJson);
        }
        if (lr.getPriority() > 0) {
            json.put("priority", lr.getPriority());
        }
        if (lr.getTimeoutForAllocateResource() > 0) {
            json.put("timeoutForAllocateResource", lr.getTimeoutForAllocateResource());
        }
        json.put("timeoutUnit", lr.getTimeoutUnit());
        if (lr.getReason() != null) {
            json.put("reason", lr.getReason());
        }
        return json;
    }

    /**
     * GET /acquire/{requestId}.
     */
    @NonNull
    public RemoteAcquireStatus getAcquireStatus(
            @NonNull RemoteConnection remote, @NonNull String authorizationHeader, @NonNull String lockId)
            throws RemoteApiException {
        String encodedLockId = URLEncoder.encode(lockId, StandardCharsets.UTF_8);
        String path = "/acquire/" + encodedLockId + "/";
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(resolve(remote, path))
                .header("Accept", "application/json")
                .timeout(requestTimeout)
                .GET();

        applyAuthorizationHeader(requestBuilder, authorizationHeader);
        HttpRequest request = requestBuilder.build();

        DecodedJsonResponse decoded = sendAndDecodeJson(remote.getServerId(), "GET", path, request);
        JSONObject response = decoded.body;
        Map<String, String> lockEnvVars = null;
        JSONObject envVarsJson = response.optJSONObject("lockEnvVars");
        if (envVarsJson != null) {
            Map<String, String> map = new LinkedHashMap<>();
            for (Object key : envVarsJson.keySet()) {
                map.put(String.valueOf(key), envVarsJson.optString(String.valueOf(key)));
            }
            lockEnvVars = Collections.unmodifiableMap(map);
        }
        return new RemoteAcquireStatus(
                extractLockId(response, lockId),
                RemoteAcquireState.fromString(response.optString("state", null)),
                response.optString("errorCode", null),
                response.optString("message", null),
                lockEnvVars);
    }

    /**
     * POST /lease/{lockId}/heartbeat.
     */
    public void heartbeatLease(
            @NonNull RemoteConnection remote, @NonNull String authorizationHeader, @NonNull String lockId)
            throws RemoteApiException {
        String encodedLockId = URLEncoder.encode(lockId, StandardCharsets.UTF_8);
        String path = "/lease/" + encodedLockId + "/heartbeat";
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(resolve(remote, path))
                .header("Accept", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.noBody());

        applyAuthorizationHeader(requestBuilder, authorizationHeader);
        HttpRequest request = requestBuilder.build();
        send(remote.getServerId(), "POST", path, request);
    }

    /**
     * POST /lease/{lockId}/release.
     */
    public void releaseLease(
            @NonNull RemoteConnection remote, @NonNull String authorizationHeader, @NonNull String lockId)
            throws RemoteApiException {
        String encodedLockId = URLEncoder.encode(lockId, StandardCharsets.UTF_8);
        String path = "/lease/" + encodedLockId + "/release";
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(resolve(remote, path))
                .header("Accept", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.noBody());

        applyAuthorizationHeader(requestBuilder, authorizationHeader);
        HttpRequest request = requestBuilder.build();
        send(remote.getServerId(), "POST", path, request);
    }

    private DecodedJsonResponse sendAndDecodeJson(String serverId, String method, String path, HttpRequest request)
            throws RemoteApiException {
        HttpResponse<String> response = send(serverId, method, path, request);
        int status = response.statusCode();
        String body = response.body();
        if (body == null || body.isEmpty()) {
            return new DecodedJsonResponse(new JSONObject(), status);
        }
        try {
            return new DecodedJsonResponse(JSONObject.fromObject(body), status);
        } catch (RuntimeException ex) {
            LOGGER.log(
                    Level.WARNING,
                    "Remote JSON parse failure: serverId={0}, method={1}, path={2}, status={3}, bodyPreview={4}",
                    new Object[] {serverId, method, path, status, abbreviateForLog(body)});
            throw new RemoteApiException(
                    "Remote response is not valid JSON for " + method + " " + path, status, serverId, "INVALID_JSON");
        }
    }

    private String extractLockId(JSONObject response, String fallbackLockId) {
        String lockId = response.optString("lockId", null);
        if (lockId == null || lockId.isEmpty()) {
            lockId = response.optString("requestId", null);
        }
        if (lockId == null || lockId.isEmpty()) {
            lockId = response.optString("leaseId", null);
        }
        if ((lockId == null || lockId.isEmpty()) && fallbackLockId != null && !fallbackLockId.isEmpty()) {
            return fallbackLockId;
        }
        return lockId;
    }

    private HttpResponse<String> send(String serverId, String method, String path, HttpRequest request)
            throws RemoteApiException {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 400) {
                String remoteCode = extractRemoteCode(response.body());
                throw new RemoteApiException(
                        "Remote API request failed: " + method + " " + path + " returned HTTP " + status,
                        status,
                        serverId,
                        remoteCode);
            }
            return response;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RemoteApiException("Remote API request interrupted: " + method + " " + path, ex, serverId);
        } catch (IOException ex) {
            LOGGER.log(
                    Level.WARNING,
                    "Remote API communication failure (fail-closed): serverId={0}, method={1}, path={2}, message={3}",
                    new Object[] {serverId, method, path, ex.getMessage()});
            throw new RemoteApiException("Remote API communication failure: " + method + " " + path, ex, serverId);
        }
    }

    private String extractRemoteCode(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return null;
        }
        try {
            JSONObject json = JSONObject.fromObject(responseBody);
            String code = json.optString("errorCode", null);
            if (code == null || code.isEmpty()) {
                code = json.optString("code", null);
            }
            return code;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private URI resolve(RemoteConnection remote, String path) throws RemoteApiException {
        String baseUrl = remote.getUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new RemoteApiException(
                    "Remote base URL is empty for serverId=" + remote.getServerId(),
                    -1,
                    remote.getServerId(),
                    "INVALID_CONFIGURATION");
        }
        try {
            String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return URI.create(normalized + RemoteClientDefaults.REMOTE_API_BASE_PATH + path);
        } catch (IllegalArgumentException ex) {
            throw new RemoteApiException(
                    "Remote base URL is invalid for serverId=" + remote.getServerId() + ": " + baseUrl,
                    -1,
                    remote.getServerId(),
                    "INVALID_CONFIGURATION");
        }
    }

    private static String abbreviateForLog(String text) {
        if (text == null) {
            return "<null>";
        }
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        if (oneLine.length() <= MAX_LOGGED_BODY_CHARS) {
            return oneLine;
        }
        return oneLine.substring(0, MAX_LOGGED_BODY_CHARS)
                + String.format(Locale.ENGLISH, "...(truncated, total=%d chars)", oneLine.length());
    }

    private void applyAuthorizationHeader(HttpRequest.Builder requestBuilder, String authorizationHeader) {
        if (authorizationHeader != null && !authorizationHeader.trim().isEmpty()) {
            requestBuilder.header("Authorization", authorizationHeader.trim());
        }
    }

    private static class DecodedJsonResponse {
        private final JSONObject body;
        private final int httpStatus;

        private DecodedJsonResponse(JSONObject body, int httpStatus) {
            this.body = body;
            this.httpStatus = httpStatus;
        }
    }
}
