/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.List;
import java.util.Map;

/**
 * Parsed status of a remote acquire request.
 */
public class RemoteAcquireStatus {

    private final String lockId;
    private final RemoteAcquireState state;
    private final String errorCode;
    private final String message;

    @CheckForNull
    private final Map<String, String> lockEnvVars;

    @CheckForNull
    private final List<String> resourceNames;

    public RemoteAcquireStatus(
            String lockId,
            RemoteAcquireState state,
            String errorCode,
            String message,
            @CheckForNull Map<String, String> lockEnvVars) {
        this(lockId, state, errorCode, message, lockEnvVars, null);
    }

    public RemoteAcquireStatus(
            String lockId,
            RemoteAcquireState state,
            String errorCode,
            String message,
            @CheckForNull Map<String, String> lockEnvVars,
            @CheckForNull List<String> resourceNames) {
        this.resourceNames = resourceNames;
        this.lockId = lockId;
        this.state = state != null ? state : RemoteAcquireState.UNKNOWN;
        this.errorCode = errorCode;
        this.message = message;
        this.lockEnvVars = lockEnvVars;
    }

    public String getLockId() {
        return lockId;
    }

    public RemoteAcquireState getState() {
        return state;
    }

    @CheckForNull
    public String getErrorCode() {
        return errorCode;
    }

    @CheckForNull
    public String getMessage() {
        return message;
    }

    @CheckForNull
    public Map<String, String> getLockEnvVars() {
        return lockEnvVars;
    }

    /** Resources the remote handed over, when it reported them. */
    @CheckForNull
    public List<String> getResourceNames() {
        return resourceNames;
    }
}
