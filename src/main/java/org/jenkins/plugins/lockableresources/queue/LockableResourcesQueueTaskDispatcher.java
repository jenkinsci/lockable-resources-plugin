/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.queue;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

@Extension
public class LockableResourcesQueueTaskDispatcher extends QueueTaskDispatcher {

    private transient Cache<Long, Date> lastLogged =
            Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();

    /** Tracks the deadline (epoch millis) for each queue item waiting for resources. */
    private final transient ConcurrentHashMap<Long, Long> deadlines = new ConcurrentHashMap<>();

    static final Logger LOGGER = Logger.getLogger(LockableResourcesQueueTaskDispatcher.class.getName());

    @Override
    public CauseOfBlockage canRun(Queue.Item item) {
        // Skip locking for multiple configuration projects,
        // only the child jobs will actually lock resources.
        if (item.task.getClass().getName().equals("hudson.matrix.MatrixProject")) {
            return null;
        }

        Job<?, ?> project = Utils.getProject(item);
        if (project == null) return null;

        // Extract build parameters so that ${PARAM} references in resource
        // names, labels, and numbers are expanded before scheduling.
        EnvVars paramEnv = Utils.getParametersAsEnvVars(item);
        LockableResourcesStruct resources = Utils.requiredResources(project, paramEnv);
        if (resources == null
                || (resources.required.isEmpty()
                        && resources.label.isEmpty()
                        && resources.getResourceMatchScriptText() == null)) {
            return null;
        }

        int resourceNumber;
        try {
            resourceNumber = Integer.parseInt(resources.requiredNumber);
        } catch (NumberFormatException e) {
            resourceNumber = 0;
        }

        LOGGER.finest(project.getName() + " trying to get resources with these details: " + resources);

        if (resourceNumber > 0 || !resources.label.isEmpty() || resources.getResourceMatchScriptText() != null) {
            Map<String, Object> params = new HashMap<>();

            // Inject Build Parameters, if possible and applicable to the "item" type
            try {
                List<ParametersAction> itemparams = item.getActions(ParametersAction.class);
                for (ParametersAction actparam : itemparams) {
                    if (actparam == null) continue;
                    for (ParameterValue p : actparam.getParameters()) {
                        if (p == null) continue;
                        params.put(p.getName(), p.getValue());
                    }
                }
            } catch (Exception ex) {
                // Report the error and go on with the build -
                // perhaps this item is not a build with args, etc.
                // Note this is likely to fail a bit later in such case.
                if (LOGGER.isLoggable(Level.WARNING)) {
                    if (lastLogged.getIfPresent(item.getId()) == null) {
                        lastLogged.put(item.getId(), new Date());
                        String itemName = project.getFullName() + " (id=" + item.getId() + ")";
                        LOGGER.log(Level.WARNING, "Failed to get build params from item " + itemName, ex);
                    }
                }
            }

            for (var ma : ExtensionList.lookup(Utils.MatrixAssist.class)) {
                params.putAll(ma.getCombination(project));
            }

            final List<LockableResource> selected;
            try {
                selected = LockableResourcesManager.get()
                        .tryQueue(resources, item.getId(), project.getFullName(), resourceNumber, params, LOGGER);
            } catch (ExecutionException ex) {
                Throwable toReport = ex.getCause();
                if (toReport == null) { // We care about the cause only
                    toReport = ex;
                }
                if (LOGGER.isLoggable(Level.WARNING)) {
                    if (lastLogged.getIfPresent(item.getId()) == null) {
                        lastLogged.put(item.getId(), new Date());

                        String itemName = project.getFullName() + " (id=" + item.getId() + ")";
                        LOGGER.log(Level.WARNING, "Failed to queue item " + itemName, toReport.getMessage());
                    }
                }

                return new BecauseResourcesQueueFailed(resources, toReport);
            }

            if (selected != null) {
                LOGGER.finest(project.getName() + " reserved resources " + selected);
                deadlines.remove(item.getId());
                return null;
            } else {
                LOGGER.finest(project.getName() + " waiting for resources");
                CauseOfBlockage timeout = checkFreestyleTimeout(item, project);
                if (timeout != null) return timeout;
                return new BecauseResourcesLocked(resources);
            }

        } else {
            if (LockableResourcesManager.get().queue(resources.required, item.getId(), project.getFullDisplayName())) {
                LOGGER.finest(project.getName() + " reserved resources " + resources.required);
                deadlines.remove(item.getId());
                return null;
            } else {
                LOGGER.finest(project.getName() + " waiting for resources " + resources.required);
                CauseOfBlockage timeout = checkFreestyleTimeout(item, project);
                if (timeout != null) return timeout;
                return new BecauseResourcesLocked(resources);
            }
        }
    }

    /**
     * Checks whether a freestyle queue item has exceeded the configured lock timeout.
     * If timed out, the item is cancelled from the Jenkins queue.
     *
     * @return a {@link BecauseResourcesTimeout} if timed out, {@code null} otherwise
     */
    private CauseOfBlockage checkFreestyleTimeout(Queue.Item item, Job<?, ?> project) {
        RequiredResourcesProperty prop = project.getProperty(RequiredResourcesProperty.class);
        if (prop == null || prop.getLockTimeout() <= 0) {
            return null;
        }

        long now = System.currentTimeMillis();
        long deadline = deadlines.computeIfAbsent(item.getId(), k -> {
            long timeoutMillis;
            try {
                timeoutMillis = TimeUnit.valueOf(prop.getLockTimeoutUnit()).toMillis(prop.getLockTimeout());
            } catch (IllegalArgumentException e) {
                timeoutMillis = TimeUnit.MINUTES.toMillis(prop.getLockTimeout());
            }
            return now + timeoutMillis;
        });

        if (now >= deadline) {
            LOGGER.log(Level.INFO, "{0} timed out waiting for lockable resources (timeout: {1} {2})", new Object[] {
                project.getFullName(),
                prop.getLockTimeout(),
                prop.getLockTimeoutUnit().toLowerCase(java.util.Locale.ENGLISH)
            });
            deadlines.remove(item.getId());
            // Cancel the queue item
            jenkins.model.Jenkins.get().getQueue().cancel(item);
            return new BecauseResourcesTimeout(project.getFullName(), prop.getLockTimeout(), prop.getLockTimeoutUnit());
        }
        return null;
    }

    public static class BecauseResourcesLocked extends CauseOfBlockage {

        private final LockableResourcesStruct rscStruct;

        public BecauseResourcesLocked(LockableResourcesStruct r) {
            this.rscStruct = r;
        }

        @Override
        public String getShortDescription() {
            if (this.rscStruct.label.isEmpty()) {
                if (!this.rscStruct.required.isEmpty()) {
                    return "Waiting for resource instances " + rscStruct.required;
                } else {
                    final String systemGroovyScript = this.rscStruct.getResourceMatchScriptText();
                    if (systemGroovyScript != null) {
                        // Empty or not... just keep the logic in sync
                        // with tryQueue() in LockableResourcesManager
                        if (systemGroovyScript.isEmpty()) {
                            return "Waiting for resources identified by custom script (which is empty)";
                        } else {
                            return "Waiting for resources identified by custom script";
                        }
                    }
                    // TODO: Developers should extend here if LockableResourcesStruct is extended
                    LOGGER.log(Level.WARNING, "Failed to classify reason of waiting for resource: " + this.rscStruct);
                    return "Waiting for lockable resources";
                }
            } else {
                return "Waiting for resources with label " + rscStruct.label;
            }
        }
    }

    // Only for UI
    @Restricted(NoExternalUse.class)
    public static class BecauseResourcesQueueFailed extends CauseOfBlockage {

        @NonNull
        private final LockableResourcesStruct resources;

        @NonNull
        private final Throwable cause;

        public BecauseResourcesQueueFailed(@NonNull LockableResourcesStruct resources, @NonNull Throwable cause) {
            this.cause = cause;
            this.resources = resources;
        }

        @Override
        public String getShortDescription() {
            // TODO: Just a copy-paste from BecauseResourcesLocked, seems strange
            String resourceInfo =
                    resources.label.isEmpty() ? resources.required.toString() : "with label " + resources.label;
            return "Execution failed while acquiring the resource " + resourceInfo + ". " + cause.getMessage();
        }
    }

    // Only for UI
    @Restricted(NoExternalUse.class)
    public static class BecauseResourcesTimeout extends CauseOfBlockage {

        private final String projectName;
        private final long timeout;
        private final String timeoutUnit;

        public BecauseResourcesTimeout(String projectName, long timeout, String timeoutUnit) {
            this.projectName = projectName;
            this.timeout = timeout;
            this.timeoutUnit = timeoutUnit;
        }

        @Override
        public String getShortDescription() {
            return projectName + " cancelled: timed out after " + timeout + " "
                    + timeoutUnit.toLowerCase(java.util.Locale.ENGLISH)
                    + " waiting for lockable resources";
        }
    }
}
