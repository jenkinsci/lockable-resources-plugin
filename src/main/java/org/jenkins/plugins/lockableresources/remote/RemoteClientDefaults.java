/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Internal defaults for remote lock client behavior.
 */
@Restricted(NoExternalUse.class)
public final class RemoteClientDefaults {

    public static final String REMOTE_API_BASE_PATH = "/lockable-resources/remote/v1";

    public static final int DEFAULT_POLL_INTERVAL_SECONDS = 3;
    public static final int DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 10;
    public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 5;

    private RemoteClientDefaults() {}
}
