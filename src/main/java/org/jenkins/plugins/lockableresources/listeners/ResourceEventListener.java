/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.listeners;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Run;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.LockableResource;

/**
 * Extension point that is notified whenever a {@link LockableResource} changes state.
 *
 * <p>Implement this extension point in your plugin to react to resource events, for example to send
 * Slack/Teams notifications, emails, or to update an external system.
 *
 * <p>Example:
 *
 * <pre>{@code
 * @Extension
 * public class MyNotifier extends ResourceEventListener {
 *     @Override
 *     public void onEvent(ResourceEvent event, List<LockableResource> resources,
 *                          Run<?, ?> build, String userName) {
 *         // send notification ...
 *     }
 * }
 * }</pre>
 */
public abstract class ResourceEventListener implements ExtensionPoint {

    private static final Logger LOGGER = Logger.getLogger(ResourceEventListener.class.getName());

    /**
     * Called when one or more resources change state.
     *
     * @param event the type of event that occurred
     * @param resources the resources whose state changed (never empty)
     * @param build the build that triggered the event (may be {@code null} for user-initiated
     *     actions)
     * @param userName the user who triggered the event (may be {@code null} for build-initiated
     *     actions)
     */
    public abstract void onEvent(
            @NonNull ResourceEvent event,
            @NonNull List<LockableResource> resources,
            @Nullable Run<?, ?> build,
            @Nullable String userName);

    /**
     * Fires the given event to all registered listeners. Exceptions thrown by individual listeners
     * are caught and logged so that a failing listener does not break core functionality.
     */
    public static void fireEvent(
            @NonNull ResourceEvent event,
            @NonNull List<LockableResource> resources,
            @Nullable Run<?, ?> build,
            @Nullable String userName) {
        if (resources.isEmpty()) {
            return;
        }
        LOGGER.fine(() -> "Firing " + event + " for " + resources);
        for (ResourceEventListener listener : ExtensionList.lookup(ResourceEventListener.class)) {
            try {
                listener.onEvent(event, resources, build, userName);
            } catch (Exception e) {
                LOGGER.log(
                        Level.WARNING,
                        "ResourceEventListener " + listener.getClass().getName() + " failed on " + event,
                        e);
            }
        }
    }
}
