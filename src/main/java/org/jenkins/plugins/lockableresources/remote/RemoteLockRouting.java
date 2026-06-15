/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import hudson.AbortException;
import java.io.PrintStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.LockStep;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.RemoteConnection;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Client-side routing helpers for {@code lock()}: decides whether a step targets a remote server and to
 * which {@link RemoteConnection}, and derives the human-readable target.
 *
 * <ul>
 *   <li><b>Peer mode</b> - {@code lock(..., serverId: 'X')} targets server {@code X} explicitly.
 *   <li><b>Delegated mode</b> - a configured {@code forcedServerId} routes every lock to that server
 *       (and overrides any DSL {@code serverId}, logging an INFO when they differ).
 * </ul>
 */
@Restricted(NoExternalUse.class)
public final class RemoteLockRouting {

    private static final Logger LOGGER = Logger.getLogger(RemoteLockRouting.class.getName());

    private RemoteLockRouting() {}

    /** A step is a remote request iff it names a {@code serverId} or a {@code forcedServerId} is configured. */
    public static boolean isRemoteRequest(LockStep step, LockableResourcesManager lrm) {
        if (step.serverId != null && !step.serverId.trim().isEmpty()) {
            return true;
        }
        String forced = lrm.getForcedServerId();
        return forced != null && !forced.trim().isEmpty();
    }

    /** Resolves the server to target: {@code forcedServerId} wins over the DSL {@code serverId} (delegated mode). */
    public static String effectiveServerId(LockStep step, LockableResourcesManager lrm, PrintStream logger) {
        String forced = lrm.getForcedServerId();
        if (forced != null && !forced.trim().isEmpty()) {
            if (step.serverId != null
                    && !step.serverId.trim().isEmpty()
                    && !step.serverId.trim().equals(forced.trim())) {
                LockableResourcesManager.printLogs(
                        "forcedServerId '" + forced + "' overrides DSL serverId '" + step.serverId + "'",
                        Level.INFO,
                        LOGGER,
                        logger);
            }
            return forced.trim();
        }
        return step.serverId;
    }

    /** Looks up the configured {@link RemoteConnection} for {@code serverId}, or fails the build. */
    public static RemoteConnection findConnection(LockableResourcesManager lrm, String serverId) throws AbortException {
        RemoteConnection remote = lrm.getRemotesAsMap().get(serverId);
        if (remote == null) {
            throw new AbortException("Remote connection not found for serverId=" + serverId);
        }
        return remote;
    }

    /** Human-readable lock target (resource name / {@code label:X} / step description) for logs and UI. */
    public static String displayTarget(LockStep step) throws AbortException {
        if (step.resource != null && !step.resource.trim().isEmpty()) {
            return step.resource.trim();
        }
        if (step.label != null && !step.label.trim().isEmpty()) {
            return "label:" + step.label.trim();
        }
        if (step.extra != null && !step.extra.isEmpty()) {
            return step.toString();
        }
        throw new AbortException("Remote lock requires at least one of: resource, label, or extra");
    }
}
