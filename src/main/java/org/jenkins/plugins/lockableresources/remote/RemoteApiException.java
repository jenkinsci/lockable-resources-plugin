/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.IOException;

/**
 * Exception from remote API communication.
 */
public class RemoteApiException extends IOException {

    private final int httpStatus;
    private final String serverId;
    private final String remoteCode;

    public RemoteApiException(String message, int httpStatus, String serverId, String remoteCode) {
        super(message);
        this.httpStatus = httpStatus;
        this.serverId = serverId;
        this.remoteCode = remoteCode;
    }

    public RemoteApiException(String message, Throwable cause, String serverId) {
        super(message, cause);
        this.httpStatus = -1;
        this.serverId = serverId;
        this.remoteCode = null;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getServerId() {
        return serverId;
    }

    @CheckForNull
    public String getRemoteCode() {
        return remoteCode;
    }
}
