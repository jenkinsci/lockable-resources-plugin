package org.jenkins.plugins.lockableresources.actions;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.model.Api;
import hudson.model.Descriptor;
import hudson.model.RootAction;
import hudson.model.Run;
import hudson.security.AccessDeniedException3;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.FreeDeadJobs;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourceProperty;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.Messages;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkins.plugins.lockableresources.queue.QueuedContextStruct;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.interceptor.RequirePOST;

@Extension
@ExportedBean
public class LockableResourcesRootAction implements RootAction {

    private static final Logger LOGGER = Logger.getLogger(LockableResourcesRootAction.class.getName());

    public static final PermissionGroup PERMISSIONS_GROUP = new PermissionGroup(
            LockableResourcesManager.class, Messages._LockableResourcesRootAction_PermissionGroup());
    public static final Permission UNLOCK = new Permission(
            PERMISSIONS_GROUP,
            "Unlock",
            Messages._LockableResourcesRootAction_UnlockPermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);
    public static final Permission RESERVE = new Permission(
            PERMISSIONS_GROUP,
            "Reserve",
            Messages._LockableResourcesRootAction_ReservePermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);
    public static final Permission STEAL = new Permission(
            PERMISSIONS_GROUP,
            "Steal",
            Messages._LockableResourcesRootAction_StealPermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);
    public static final Permission VIEW = new Permission(
            PERMISSIONS_GROUP,
            "View",
            Messages._LockableResourcesRootAction_ViewPermission_Description(),
            Jenkins.READ,
            PermissionScope.JENKINS);
    public static final Permission QUEUE = new Permission(
            PERMISSIONS_GROUP,
            "Queue",
            Messages._LockableResourcesRootAction_QueueChangeOrderPermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);
    public static final Permission CONFIGURE = new Permission(
            PERMISSIONS_GROUP,
            "Configure",
            Messages._LockableResourcesRootAction_ConfigurePermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);

    public static final String ICON = "symbol-lock-closed";

    @CheckForNull
    @Override
    public String getIconFileName() {
        return Jenkins.get().hasPermission(VIEW) ? ICON : null;
    }

    /**
     * Returns the ManagementLink instance for use by the Jelly view when rendering
     * within the Manage Jenkins layout.
     * Used by {@code index.jelly}.
     */
    @Restricted(NoExternalUse.class)
    public LockableResourcesManagementLink getManagementLink() {
        return ExtensionList.lookupSingleton(LockableResourcesManagementLink.class);
    }

    public Api getApi() {
        return new Api(this);
    }

    @CheckForNull
    public String getUserName() {
        return LockableResource.getUserName();
    }

    @CheckForNull
    @Override
    public String getDisplayName() {
        return Jenkins.get().hasPermission(VIEW) ? Messages.LockableResourcesRootAction_PermissionGroup() : null;
    }

    @Override
    public String getUrlName() {
        return "lockable-resources";
    }

    // ---------------------------------------------------------------------------
    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        long remainingMin = minutes % 60;
        if (hours < 24) return hours + "h " + remainingMin + "m";
        long days = hours / 24;
        long remainingHours = hours % 24;
        return days + "d " + remainingHours + "h";
    }

    // ---------------------------------------------------------------------------
    /** Returns a summary of resource states for the overview tab. */
    @Restricted(NoExternalUse.class) // used by jelly
    public Summary getSummary() {
        Jenkins.get().checkPermission(VIEW);

        int locked = 0;
        int reserved = 0;
        int queued = 0;
        int free = 0;
        int total = 0;

        for (LockableResource r : LockableResourcesManager.get().getReadOnlyResources()) {
            total++;
            if (r.getReservedBy() != null) {
                reserved++;
            } else if (r.isLocked()) {
                locked++;
            } else {
                // Queued resources are counted as free since the queued state
                // is transient and nearly invisible in practice.
                free++;
            }
        }

        int queueItems = 0;
        int distinctBuildsWaiting = 0;
        String oldestBuildName = null;
        String oldestBuildUrl = null;
        long oldestQueuedAt = Long.MAX_VALUE;
        Map<String, Integer> resourceDemand = new HashMap<>();
        for (QueuedContextStruct context : LockableResourcesManager.get().getCurrentQueuedContext()) {
            queueItems += context.getResources().size();
            distinctBuildsWaiting++;
            Run<?, ?> run = context.getBuild();
            for (LockableResourcesStruct rs : context.getResources()) {
                if (rs.queuedAt > 0 && rs.queuedAt < oldestQueuedAt) {
                    oldestQueuedAt = rs.queuedAt;
                    oldestBuildName = (run != null) ? run.getFullDisplayName() : null;
                    oldestBuildUrl = (run != null) ? run.getUrl() : null;
                }
                for (LockableResource r : rs.required) {
                    resourceDemand.merge(r.getName(), 1, Integer::sum);
                }
            }
        }
        String oldestWaitTime = null;
        if (oldestBuildName != null && oldestQueuedAt < Long.MAX_VALUE) {
            long elapsed = System.currentTimeMillis() - oldestQueuedAt;
            oldestWaitTime = formatDuration(elapsed);
        }
        String mostContendedResource = null;
        int mostContendedCount = 0;
        for (Map.Entry<String, Integer> entry : resourceDemand.entrySet()) {
            if (entry.getValue() > mostContendedCount) {
                mostContendedCount = entry.getValue();
                mostContendedResource = entry.getKey();
            }
        }

        int labelsCount = getLabelsList().size();

        List<LockableResourcesLabel> topLabels = getLabelsList().values().stream()
                .sorted((a, b) -> Integer.compare(b.getAssigned(), a.getAssigned()))
                .limit(3)
                .collect(Collectors.toList());

        return new Summary(
                total,
                locked,
                reserved,
                queued,
                free,
                queueItems,
                distinctBuildsWaiting,
                labelsCount,
                topLabels,
                oldestBuildName,
                oldestBuildUrl,
                oldestWaitTime,
                mostContendedResource,
                mostContendedCount);
    }

    // ---------------------------------------------------------------------------
    @Restricted(NoExternalUse.class)
    public static final class Summary {
        private final int total;
        private final int locked;
        private final int reserved;
        private final int queued;
        private final int free;
        private final int queueItems;
        private final int distinctBuildsWaiting;
        private final int labelsCount;
        private final List<LockableResourcesLabel> topLabels;
        private final String oldestBuildName;
        private final String oldestBuildUrl;
        private final String oldestWaitTime;
        private final String mostContendedResource;
        private final int mostContendedCount;

        Summary(
                int total,
                int locked,
                int reserved,
                int queued,
                int free,
                int queueItems,
                int distinctBuildsWaiting,
                int labelsCount,
                List<LockableResourcesLabel> topLabels,
                String oldestBuildName,
                String oldestBuildUrl,
                String oldestWaitTime,
                String mostContendedResource,
                int mostContendedCount) {
            this.total = total;
            this.locked = locked;
            this.reserved = reserved;
            this.queued = queued;
            this.free = free;
            this.queueItems = queueItems;
            this.distinctBuildsWaiting = distinctBuildsWaiting;
            this.labelsCount = labelsCount;
            this.topLabels = topLabels;
            this.oldestBuildName = oldestBuildName;
            this.oldestBuildUrl = oldestBuildUrl;
            this.oldestWaitTime = oldestWaitTime;
            this.mostContendedResource = mostContendedResource;
            this.mostContendedCount = mostContendedCount;
        }

        public int getTotal() {
            return total;
        }

        public int getLocked() {
            return locked;
        }

        public int getReserved() {
            return reserved;
        }

        public int getQueued() {
            return queued;
        }

        public int getFree() {
            return free;
        }

        public int getQueueItems() {
            return queueItems;
        }

        public int getDistinctBuildsWaiting() {
            return distinctBuildsWaiting;
        }

        public int getLabelsCount() {
            return labelsCount;
        }

        public List<LockableResourcesLabel> getTopLabels() {
            return topLabels;
        }

        public int getLockedPct() {
            return total > 0 ? (locked * 100) / total : 0;
        }

        public int getReservedPct() {
            return total > 0 ? (reserved * 100) / total : 0;
        }

        public int getFreePct() {
            return total > 0 ? (free * 100) / total : 0;
        }

        public String getOldestBuildName() {
            return oldestBuildName;
        }

        public String getOldestBuildUrl() {
            return oldestBuildUrl;
        }

        public String getOldestWaitTime() {
            return oldestWaitTime;
        }

        public String getMostContendedResource() {
            return mostContendedResource;
        }

        public int getMostContendedCount() {
            return mostContendedCount;
        }
    }

    // ---------------------------------------------------------------------------
    /**
     * Get a list of resources
     *
     * @return All resources.
     */
    @Exported
    @Restricted(NoExternalUse.class) // used by jelly
    public List<LockableResource> getResources() {
        return LockableResourcesManager.get().getReadOnlyResources();
    }

    // ---------------------------------------------------------------------------
    /**
     * Get a list of all labels
     *
     * @return All possible labels.
     */
    @Restricted(NoExternalUse.class) // used by jelly
    public LinkedHashMap<String, LockableResourcesLabel> getLabelsList() {
        LinkedHashMap<String, LockableResourcesLabel> map = new LinkedHashMap<>();

        for (LockableResource r : LockableResourcesManager.get().getReadOnlyResources()) {
            if (r == null || r.getName().isEmpty()) {
                continue; // defensive, shall never happens, but ...
            }
            List<String> assignedLabels = r.getLabelsAsList();
            if (assignedLabels.isEmpty()) {
                continue;
            }

            for (String labelString : assignedLabels) {
                if (labelString == null || labelString.isEmpty()) {
                    continue; // defensive, shall never happens, but ...
                }
                LockableResourcesLabel label = map.get(labelString);
                if (label == null) {
                    label = new LockableResourcesLabel(labelString);
                }

                label.update(r);

                map.put(labelString, label);
            }
        }

        return map;
    }

    // ---------------------------------------------------------------------------
    public static class LockableResourcesLabel {
        String name;
        int free;
        int assigned;

        // -------------------------------------------------------------------------
        public LockableResourcesLabel(String _name) {
            this.name = _name;
            this.free = 0;
            this.assigned = 0;
        }

        // -------------------------------------------------------------------------
        public void update(LockableResource resource) {
            this.assigned++;
            if (resource.isFree()) free++;
        }

        // -------------------------------------------------------------------------
        public String getName() {
            return this.name;
        }

        // -------------------------------------------------------------------------
        public int getFree() {
            return this.free;
        }

        // -------------------------------------------------------------------------
        public int getAssigned() {
            return this.assigned;
        }

        // -------------------------------------------------------------------------
        public int getPercentage() {
            if (this.assigned == 0) {
                return this.assigned;
            }
            return (int) ((double) this.free / (double) this.assigned * 100);
        }
    }

    // ---------------------------------------------------------------------------
    // used by by
    // src\main\resources\org\jenkins\plugins\lockableresources\actions\LockableResourcesRootAction\tableResources\table.jelly
    @Restricted(NoExternalUse.class)
    public LockableResource getResource(final String resourceName) {
        return LockableResourcesManager.get().fromName(resourceName);
    }

    // ---------------------------------------------------------------------------
    /**
     * Get amount of free resources assigned to given *labelString*
     *
     * @param labelString Label to search.
     * @return Amount of free labels.
     */
    @Restricted(NoExternalUse.class) // used by jelly
    @Deprecated // slow down plugin execution due concurrent modification checks
    public int getFreeResourceAmount(final String labelString) {
        this.informPerformanceIssue();
        LockableResourcesLabel label = this.getLabelsList().get(labelString);
        return (label == null) ? 0 : label.getFree();
    }

    // ---------------------------------------------------------------------------
    /**
     * Get percentage (0-100) usage of resources assigned to given *labelString*
     *
     * <p>Used by {@code actions/LockableResourcesRootAction/index.jelly}
     *
     * @since 2.19
     * @param labelString Label to search.
     * @return Percentage usages of *labelString* around all resources
     */
    @Restricted(NoExternalUse.class) // used by jelly
    @Deprecated // slow down plugin execution due concurrent modification checks
    public int getFreeResourcePercentage(final String labelString) {
        this.informPerformanceIssue();
        LockableResourcesLabel label = this.getLabelsList().get(labelString);
        return (label == null) ? 0 : label.getPercentage();
    }

    // ---------------------------------------------------------------------------
    /**
     * Get all existing labels as list.
     *
     * @return All possible labels.
     */
    @Restricted(NoExternalUse.class) // used by jelly
    @Deprecated // slow down plugin execution due concurrent modification checks
    public Set<String> getAllLabels() {
        this.informPerformanceIssue();
        return LockableResourcesManager.get().getAllLabels();
    }

    // ---------------------------------------------------------------------------
    /**
     * Get amount of all labels.
     *
     * @return Amount of all labels.
     */
    @Restricted(NoExternalUse.class) // used by jelly
    @Deprecated // slow down plugin execution due concurrent modification checks
    public int getNumberOfAllLabels() {
        this.informPerformanceIssue();
        return this.getLabelsList().size();
    }

    // ---------------------------------------------------------------------------
    /**
     * Get amount of resources assigned to given *labelString*
     *
     * <p>Used by {@code actions/LockableResourcesRootAction/index.jelly}
     *
     * @param labelString Label to search.
     * @return Amount of assigned resources.
     */
    @Restricted(NoExternalUse.class) // used by jelly
    @Deprecated // slow down plugin execution due concurrent modification checks
    public int getAssignedResourceAmount(String labelString) {
        this.informPerformanceIssue();
        return LockableResourcesManager.get().getResourcesWithLabel(labelString).size();
    }

    // ---------------------------------------------------------------------------
    private void informPerformanceIssue() {
        String method = Thread.currentThread().getStackTrace()[2].getMethodName();
        StringBuilder buf = new StringBuilder();
        for (StackTraceElement st : Thread.currentThread().getStackTrace()) {
            buf.append("\n").append(st);
        }
        LOGGER.warning("lockable-resources-plugin: The method "
                + method
                + " has been deprecated due performance issues. When you see this message, please inform plugin developers:"
                + buf);
    }

    // ---------------------------------------------------------------------------
    @Restricted(NoExternalUse.class) // used by jelly
    public Queue getQueue() throws Descriptor.FormException {
        List<QueuedContextStruct> currentQueueContext =
                List.copyOf(LockableResourcesManager.get().getCurrentQueuedContext());
        Queue queue = new Queue();

        for (QueuedContextStruct context : currentQueueContext) {
            for (LockableResourcesStruct resourceStruct : context.getResources()) {
                queue.add(resourceStruct, context);
            }
        }

        return queue;
    }

    // ---------------------------------------------------------------------------
    public static class Queue {

        List<QueueStruct> queue;
        QueueStruct oldest;

        // -------------------------------------------------------------------------
        @Restricted(NoExternalUse.class) // used by jelly
        public Queue() {
            this.queue = new ArrayList<>();
        }

        // -------------------------------------------------------------------------
        @Restricted(NoExternalUse.class) // used by jelly
        public void add(final LockableResourcesStruct resourceStruct, final QueuedContextStruct context)
                throws Descriptor.FormException {
            QueueStruct queueStruct = new QueueStruct(resourceStruct, context);
            queue.add(queueStruct);
            if (resourceStruct.queuedAt == 0) {
                // Older versions of this plugin might miss this information.
                // Therefore skip it here.
                return;
            }
            if (oldest == null || oldest.getQueuedAt() > queueStruct.getQueuedAt()) {
                oldest = queueStruct;
            }
        }

        // -------------------------------------------------------------------------
        @Restricted(NoExternalUse.class) // used by jelly
        public List<QueueStruct> getAll() {
            return Collections.unmodifiableList(this.queue);
        }

        // -------------------------------------------------------------------------
        @Restricted(NoExternalUse.class) // used by jelly
        public QueueStruct getOldest() {
            return this.oldest;
        }

        // -------------------------------------------------------------------------
        @Restricted(NoExternalUse.class) // used by jelly
        public static class QueueStruct {
            List<LockableResource> requiredResources;
            String requiredLabel;
            String groovyScript;
            String requiredNumber;
            long queuedAt = 0;
            int priority = 0;
            String id = null;
            Run<?, ?> build;

            public QueueStruct(final LockableResourcesStruct resourceStruct, final QueuedContextStruct context)
                    throws Descriptor.FormException {
                this.requiredResources = resourceStruct.required;
                this.requiredLabel = resourceStruct.label;
                this.requiredNumber = resourceStruct.requiredNumber;
                this.queuedAt = resourceStruct.queuedAt;
                this.build = context.getBuild();
                this.priority = context.getPriority();
                this.id = context.getId();

                final SecureGroovyScript systemGroovyScript = resourceStruct.getResourceMatchScript();
                if (systemGroovyScript != null) {
                    this.groovyScript = systemGroovyScript.getScript();
                }
            }

            // -----------------------------------------------------------------------
            /** */
            @Restricted(NoExternalUse.class) // used by jelly
            public List<LockableResource> getRequiredResources() {
                return this.requiredResources;
            }

            // -----------------------------------------------------------------------
            /** */
            @NonNull
            @Restricted(NoExternalUse.class) // used by jelly
            public String getRequiredLabel() {
                return this.requiredLabel == null ? "N/A" : this.requiredLabel;
            }

            // -----------------------------------------------------------------------
            /** */
            @NonNull
            @Restricted(NoExternalUse.class) // used by jelly
            public String getRequiredNumber() {
                return this.requiredNumber == null ? "0" : this.requiredNumber;
            }

            // -----------------------------------------------------------------------
            /** */
            @NonNull
            @Restricted(NoExternalUse.class) // used by jelly
            public String getGroovyScript() {
                return this.groovyScript == null ? "N/A" : this.groovyScript;
            }

            // -----------------------------------------------------------------------
            /** */
            @Restricted(NoExternalUse.class) // used by jelly
            public Run<?, ?> getBuild() {
                return this.build;
            }

            // -----------------------------------------------------------------------
            /** */
            @Restricted(NoExternalUse.class) // used by jelly
            public long getQueuedAt() {
                return this.queuedAt;
            }

            // -----------------------------------------------------------------------
            /** Check if the queue takes too long. At the moment "too long" means over 1 hour. */
            @Restricted(NoExternalUse.class) // used by jelly
            public boolean takeTooLong() {
                return (new Date().getTime() - this.queuedAt) > 3600000L;
            }

            // -----------------------------------------------------------------------
            /** Returns timestamp when the resource has been added into queue. */
            @Restricted(NoExternalUse.class) // used by jelly
            public Date getQueuedTimestamp() {
                return new Date(this.queuedAt);
            }

            // -----------------------------------------------------------------------
            /** Returns queue priority. */
            @Restricted(NoExternalUse.class) // used by jelly
            public int getPriority() {
                if (this.id == null) {
                    // defensive
                    // in case of jenkins update from older version and you have some queue
                    // might happens, that there are no priority set
                    return 0;
                }
                return this.priority;
            }

            // -----------------------------------------------------------------------
            /** Returns queue ID. */
            @Restricted(NoExternalUse.class)
            public String getId() {
                if (this.id == null) {
                    // defensive
                    // in case of jenkins update from older version and you have some queue
                    // might happens, that there are no priority set
                    return "NN";
                }
                return this.id;
            }

            @Restricted(NoExternalUse.class) // used by jelly
            public boolean resourcesMatch() {
                return (requiredResources != null && requiredResources.size() > 0);
            }

            // -----------------------------------------------------------------------
            @Restricted(NoExternalUse.class) // used by jelly
            public boolean labelsMatch() {
                return (requiredLabel != null);
            }

            // -----------------------------------------------------------------------
            @Restricted(NoExternalUse.class) // used by jelly
            public boolean scriptMatch() {
                return (groovyScript != null && !groovyScript.isEmpty());
            }
        }
    }

    // ---------------------------------------------------------------------------
    /** Returns current queue */
    @Restricted(NoExternalUse.class) // used by jelly
    @Deprecated // slow down plugin execution due concurrent modification checks
    public List<QueuedContextStruct> getCurrentQueuedContext() {
        return LockableResourcesManager.get().getCurrentQueuedContext();
    }

    // ---------------------------------------------------------------------------
    /** Returns current queue */
    @Restricted(NoExternalUse.class) // used by jelly
    @CheckForNull
    @Deprecated // slow down plugin execution due concurrent modification checks
    public LockableResourcesStruct getOldestQueue() {
        LockableResourcesStruct oldest = null;
        for (QueuedContextStruct context : this.getCurrentQueuedContext()) {
            for (LockableResourcesStruct resourceStruct : context.getResources()) {
                if (resourceStruct.queuedAt == 0) {
                    // Older versions of this plugin might miss this information.
                    // Therefore skip it here.
                    continue;
                }
                if (oldest == null || oldest.queuedAt > resourceStruct.queuedAt) {
                    oldest = resourceStruct;
                }
            }
        }
        return oldest;
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doUnlock(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(UNLOCK);

        List<LockableResource> resources = this.getResourcesFromRequest(req, rsp);
        if (resources == null) {
            return;
        }

        LockableResourcesManager.get().unlockResources(resources);

        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doReserve(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(RESERVE);

        List<LockableResource> resources = this.getResourcesFromRequest(req, rsp);
        if (resources == null) {
            return;
        }

        String reason = Util.fixEmptyAndTrim(req.getParameter("reason"));

        LOGGER.info("doReserve called for resources=" + LockableResourcesManager.getResourcesNames(resources)
                + " reason='" + reason + "' fromIP=" + req.getRemoteAddr());

        String userName = getUserName();
        if (userName == null) {
            LOGGER.warning("doReserve: userName is null (unauthenticated?) for resources="
                    + LockableResourcesManager.getResourcesNames(resources));
            rsp.sendError(401, Messages.error_notAuthenticated());
            return;
        }

        boolean ok = LockableResourcesManager.get().reserve(resources, userName, reason);
        if (!ok) {
            LOGGER.info("doReserve failed - resource already locked: "
                    + LockableResourcesManager.getResourcesNames(resources));
            rsp.sendError(
                    423, Messages.error_resourceAlreadyLocked(LockableResourcesManager.getResourcesNames(resources)));
            return;
        }
        LOGGER.info("doReserve succeeded for user='" + userName + "' resources="
                + LockableResourcesManager.getResourcesNames(resources));
        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doSteal(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(STEAL);

        List<LockableResource> resources = this.getResourcesFromRequest(req, rsp);
        if (resources == null) {
            return;
        }

        String reason = Util.fixEmptyAndTrim(req.getParameter("reason"));

        String userName = getUserName();
        if (userName == null) {
            rsp.sendError(401, Messages.error_notAuthenticated());
            return;
        }

        LockableResourcesManager.get().steal(resources, userName, reason);
        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doReassign(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(STEAL);

        String userName = getUserName();
        if (userName == null) {
            // defensive: this can not happens because we check you permissions few lines before
            // therefore you must be logged in
            throw new AccessDeniedException3(Jenkins.getAuthentication2(), STEAL);
        }

        List<LockableResource> resources = this.getResourcesFromRequest(req, rsp);
        if (resources == null) {
            return;
        }

        for (LockableResource resource : resources) {
            if (userName.equals(resource.getReservedBy())) {
                // Can not achieve much by re-assigning the
                // resource I already hold to myself again,
                // that would just burn the compute resources.
                // ...unless something catches the event? (TODO?)
                return;
            }
        }

        LockableResourcesManager.get().reassign(resources, userName);

        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doUnreserve(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(RESERVE);

        List<LockableResource> resources = this.getResourcesFromRequest(req, rsp);
        if (resources == null) {
            return;
        }

        String userName = getUserName();
        for (LockableResource resource : resources) {
            if ((userName == null || !userName.equals(resource.getReservedBy()))
                    && !Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                throw new AccessDeniedException3(Jenkins.getAuthentication2(), RESERVE);
            }
        }

        LockableResourcesManager.get().unreserve(resources);

        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doReset(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(UNLOCK);
        // Should this also be permitted by "STEAL"?..

        List<LockableResource> resources = this.getResourcesFromRequest(req, rsp);
        if (resources == null) {
            return;
        }

        LockableResourcesManager.get().reset(resources);

        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doRecycleDeadLocks(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        FreeDeadJobs.freePostMortemResources();
        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doSaveNote(final StaplerRequest2 req, final StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(RESERVE);

        String resourceName = req.getParameter("resource");
        if (resourceName == null) {
            resourceName = req.getParameter("resourceName");
        }

        final LockableResource resource = getResource(resourceName);
        if (resource == null) {
            rsp.sendError(404, Messages.error_resourceDoesNotExist(resourceName));
        } else {
            String resourceNote = req.getParameter("note");
            if (resourceNote == null) {
                resourceNote = req.getParameter("resourceNote");
            }
            resource.setNote(resourceNote);
            LockableResourcesManager.get().save();

            rsp.forwardToPreviousPage(req);
        }
    }

    // ---------------------------------------------------------------------------
    /** Change queue order (item position) */
    @Restricted(NoExternalUse.class) // used by jelly
    @RequirePOST
    public void doChangeQueueOrder(final StaplerRequest2 req, final StaplerResponse2 rsp)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(QUEUE);

        final String queueId = req.getParameter("id");
        final String newIndexStr = req.getParameter("index");

        LOGGER.fine("doChangeQueueOrder, id: " + queueId + " newIndexStr: " + newIndexStr);

        final int newIndex;
        try {
            newIndex = Integer.parseInt(newIndexStr);
        } catch (NumberFormatException e) {
            rsp.sendError(423, Messages.error_isNotANumber(newIndexStr));
            return;
        }

        try {
            LockableResourcesManager.get().changeQueueOrder(queueId, newIndex - 1);
        } catch (IOException e) {
            rsp.sendError(423, e.toString().replace("java.io.IOException: ", ""));
            return;
        }

        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    /** Returns a page of queue items as JSON for server-side pagination. */
    @Restricted(NoExternalUse.class)
    public void doGetQueuePage(final StaplerRequest2 req, final StaplerResponse2 rsp)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(VIEW);

        int page = 1;
        int size = 25;
        String filter = Util.fixEmptyAndTrim(req.getParameter("filter"));
        String typeFilter = Util.fixEmptyAndTrim(req.getParameter("type"));
        String requestFilter = Util.fixEmptyAndTrim(req.getParameter("request"));
        String requestedByFilter = Util.fixEmptyAndTrim(req.getParameter("requestedBy"));

        try {
            String pageParam = req.getParameter("page");
            if (pageParam != null) page = Math.max(1, Integer.parseInt(pageParam));
        } catch (NumberFormatException ignored) {
        }
        try {
            String sizeParam = req.getParameter("size");
            if (sizeParam != null) size = Math.max(1, Math.min(200, Integer.parseInt(sizeParam)));
        } catch (NumberFormatException ignored) {
        }

        Queue queue;
        try {
            queue = getQueue();
        } catch (Descriptor.FormException e) {
            rsp.sendError(500, e.getMessage());
            return;
        }

        List<Queue.QueueStruct> allItems = queue.getAll();

        // Apply filter if provided
        if (filter != null || typeFilter != null || requestFilter != null || requestedByFilter != null) {
            final String lowerFilter = filter != null ? filter.toLowerCase(Locale.ENGLISH) : null;
            final String lowerTypeFilter = typeFilter != null ? typeFilter.toLowerCase(Locale.ENGLISH) : null;
            final String lowerRequestFilter = requestFilter != null ? requestFilter.toLowerCase(Locale.ENGLISH) : null;
            final String lowerRequestedByFilter =
                    requestedByFilter != null ? requestedByFilter.toLowerCase(Locale.ENGLISH) : null;
            allItems = allItems.stream()
                    .filter(item -> {
                        if (lowerTypeFilter != null
                                && !getQueueItemType(item)
                                        .toLowerCase(Locale.ENGLISH)
                                        .contains(lowerTypeFilter)) {
                            return false;
                        }
                        if (lowerRequestFilter != null
                                && !getQueueItemRequestText(item)
                                        .toLowerCase(Locale.ENGLISH)
                                        .contains(lowerRequestFilter)) {
                            return false;
                        }
                        if (lowerRequestedByFilter != null) {
                            Run<?, ?> requestedByBuild = item.getBuild();
                            String requestedByText =
                                    requestedByBuild != null ? requestedByBuild.getFullDisplayName() : "";
                            if (!requestedByText.toLowerCase(Locale.ENGLISH).contains(lowerRequestedByFilter)) {
                                return false;
                            }
                        }

                        if (lowerFilter == null) {
                            return true;
                        }

                        if (item.resourcesMatch()) {
                            for (LockableResource r : item.getRequiredResources()) {
                                if (r.getName().toLowerCase(Locale.ENGLISH).contains(lowerFilter)) return true;
                            }
                        }
                        if (item.labelsMatch()
                                && item.getRequiredLabel()
                                        .toLowerCase(Locale.ENGLISH)
                                        .contains(lowerFilter)) {
                            return true;
                        }
                        Run<?, ?> b = item.getBuild();
                        if (b != null
                                && b.getFullDisplayName()
                                        .toLowerCase(Locale.ENGLISH)
                                        .contains(lowerFilter)) {
                            return true;
                        }
                        return item.getId().toLowerCase(Locale.ENGLISH).contains(lowerFilter);
                    })
                    .collect(Collectors.toList());
        }

        int total = allItems.size();
        int pages = (int) Math.ceil((double) total / size);
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<Queue.QueueStruct> pageItems = allItems.subList(fromIndex, toIndex);

        // Build JSON response
        net.sf.json.JSONObject result = new net.sf.json.JSONObject();
        result.put("page", page);
        result.put("size", size);
        result.put("total", total);
        result.put("pages", pages);

        net.sf.json.JSONArray items = new net.sf.json.JSONArray();
        int index = fromIndex;
        for (Queue.QueueStruct item : pageItems) {
            net.sf.json.JSONObject obj = new net.sf.json.JSONObject();
            obj.put("index", index + 1);
            obj.put("id", item.getId());
            obj.put("priority", item.getPriority());
            obj.put("queuedAt", item.getQueuedAt());
            obj.put("requestText", getQueueItemRequestText(item));
            obj.put(
                    "queuedAtHuman",
                    item.getQueuedAt() > 0
                            ? Util.getTimeSpanString(System.currentTimeMillis() - item.getQueuedAt())
                            : "");

            Run<?, ?> build = item.getBuild();
            if (build != null) {
                obj.put("requestedBy", build.getFullDisplayName());
                obj.put("requestedByUrl", build.getUrl());
            } else {
                obj.put("requestedBy", "");
                obj.put("requestedByUrl", "");
            }

            if (item.resourcesMatch()) {
                obj.put("type", "resources");
                net.sf.json.JSONArray resources = new net.sf.json.JSONArray();
                for (LockableResource r : item.getRequiredResources()) {
                    net.sf.json.JSONObject ro = new net.sf.json.JSONObject();
                    ro.put("name", r.getName());
                    ro.put("ephemeral", r.isEphemeral());
                    ro.put("description", r.getDescription() != null ? r.getDescription() : "");
                    resources.add(ro);
                }
                obj.put("request", resources);
                obj.put("reason", "");
            } else if (item.labelsMatch()) {
                obj.put("type", "label");
                obj.put("request", item.getRequiredLabel());
                String requiredNum = item.getRequiredNumber();
                obj.put("reason", "0".equals(requiredNum) ? "all" : requiredNum + " required");
            } else {
                obj.put("type", "groovy");
                obj.put("request", "Groovy expression");
                obj.put("reason", "");
            }

            items.add(obj);
            index++;
        }
        result.put("items", items);

        // Warning info
        Queue.QueueStruct oldest = queue.getOldest();
        if (oldest != null && oldest.takeTooLong()) {
            result.put("warningCount", allItems.size());
            result.put("warningAge", Util.getTimeSpanString(System.currentTimeMillis() - oldest.getQueuedAt()));
        }

        rsp.setContentType("application/json;charset=UTF-8");
        rsp.getWriter().write(result.toString());
    }

    private String getQueueItemType(final Queue.QueueStruct item) {
        if (item.resourcesMatch()) {
            return "resources";
        }
        if (item.labelsMatch()) {
            return "label";
        }
        return "groovy";
    }

    private String getQueueItemRequestText(final Queue.QueueStruct item) {
        if (item.resourcesMatch()) {
            return item.getRequiredResources().stream()
                    .map(LockableResource::getName)
                    .collect(Collectors.joining(", "));
        }
        if (item.labelsMatch()) {
            return item.getRequiredLabel();
        }
        return "Groovy expression";
    }

    // ---------------------------------------------------------------------------
    /** Parse properties from a JSON array. */
    private List<LockableResourceProperty> parsePropertiesFromJson(net.sf.json.JSONObject json) {
        List<LockableResourceProperty> properties = new ArrayList<>();
        net.sf.json.JSONArray propsArr = json.optJSONArray("properties");
        if (propsArr != null) {
            for (int i = 0; i < propsArr.size(); i++) {
                net.sf.json.JSONObject p = propsArr.getJSONObject(i);
                String pName = Util.fixEmptyAndTrim(p.optString("name", null));
                String pValue = p.optString("value", "");
                if (pName != null) {
                    LockableResourceProperty prop = new LockableResourceProperty();
                    prop.setName(pName);
                    prop.setValue(pValue);
                    properties.add(prop);
                }
            }
        }
        return properties;
    }

    // ---------------------------------------------------------------------------
    /** Create a new lockable resource from the management page. */
    @Restricted(NoExternalUse.class)
    @RequirePOST
    public void doCreateResource(final StaplerRequest2 req, final StaplerResponse2 rsp)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(CONFIGURE);

        String name;
        String description;
        String labels;
        List<LockableResourceProperty> properties = new ArrayList<>();

        String contentType = req.getContentType();
        if (contentType != null && contentType.contains("application/json")) {
            // JSON body from the inline dialog
            net.sf.json.JSONObject json = net.sf.json.JSONObject.fromObject(
                    new String(req.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            name = Util.fixEmptyAndTrim(json.optString("name", null));
            description = Util.fixEmptyAndTrim(json.optString("description", null));
            labels = Util.fixEmptyAndTrim(json.optString("labels", null));
            properties = parsePropertiesFromJson(json);
        } else {
            // Form-encoded (e.g. from tests)
            name = Util.fixEmptyAndTrim(req.getParameter("name"));
            description = Util.fixEmptyAndTrim(req.getParameter("description"));
            labels = Util.fixEmptyAndTrim(req.getParameter("labels"));
        }

        if (name == null) {
            rsp.sendError(400, "Resource name is required.");
            return;
        }

        LockableResourcesManager manager = LockableResourcesManager.get();
        if (manager.fromName(name) != null) {
            rsp.sendError(409, Messages.error_resourceAlreadyExists(name));
            return;
        }

        LockableResource resource = new LockableResource(name);

        if (description != null) {
            resource.setDescription(description);
        }
        if (labels != null) {
            resource.setLabels(labels);
        }
        if (!properties.isEmpty()) {
            resource.setProperties(properties);
        }

        boolean added = manager.addResource(resource, /*doSave*/ true);
        if (!added) {
            rsp.sendError(409, Messages.error_resourceAlreadyExists(name));
            return;
        }

        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    /** Edit an existing lockable resource from the management page. */
    @Restricted(NoExternalUse.class)
    @RequirePOST
    public void doEditResource(final StaplerRequest2 req, final StaplerResponse2 rsp)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(CONFIGURE);

        String contentType = req.getContentType();
        if (contentType == null || !contentType.contains("application/json")) {
            rsp.sendError(400, "JSON body required.");
            return;
        }

        net.sf.json.JSONObject json = net.sf.json.JSONObject.fromObject(
                new String(req.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));

        String name = Util.fixEmptyAndTrim(json.optString("name", null));
        if (name == null) {
            rsp.sendError(400, "Resource name is required.");
            return;
        }

        LockableResourcesManager manager = LockableResourcesManager.get();
        synchronized (LockableResourcesManager.syncResources) {
            LockableResource resource = manager.fromName(name);
            if (resource == null) {
                rsp.sendError(404, Messages.error_resourceDoesNotExist(name));
                return;
            }

            resource.setDescription(Util.fixEmptyAndTrim(json.optString("description", null)));
            resource.setLabels(Util.fixEmptyAndTrim(json.optString("labels", null)));
            resource.setProperties(parsePropertiesFromJson(json));

            manager.save();
        }
        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    /** Delete a lockable resource from the management page. */
    @Restricted(NoExternalUse.class)
    @RequirePOST
    public void doDeleteResource(final StaplerRequest2 req, final StaplerResponse2 rsp)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(CONFIGURE);

        String name = req.getParameter("resource");
        LockableResourcesManager manager = LockableResourcesManager.get();
        synchronized (LockableResourcesManager.syncResources) {
            LockableResource resource = manager.fromName(name);
            if (resource == null) {
                rsp.sendError(404, Messages.error_resourceDoesNotExist(name));
                return;
            }

            if (!resource.isFree()) {
                String cause = resource.isLocked() ? "locked" : resource.isReserved() ? "reserved" : "queued";
                rsp.sendError(423, Messages.error_resourceAlreadyLocked(name) + " (" + cause + ")");
                return;
            }

            List<LockableResource> toRemove = new ArrayList<>();
            toRemove.add(resource);
            manager.removeResources(toRemove);
            manager.save();
        }
        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    private List<LockableResource> getResourcesFromRequest(final StaplerRequest2 req, final StaplerResponse2 rsp)
            throws IOException, ServletException {
        // todo, when you try to improve the API to use multiple resources (a list instead of single
        // one)
        // this will be the best place to change it. Probably it will be enough to add a code piece here
        // like req.getParameter("resources"); And split the content by some delimiter like ' ' (space)
        String name = req.getParameter("resource");
        LockableResource r = LockableResourcesManager.get().fromName(name);
        if (r == null) {
            rsp.sendError(404, Messages.error_resourceDoesNotExist(name));
            return null;
        }

        List<LockableResource> resources = new ArrayList<>();
        resources.add(r);
        return resources;
    }
}
