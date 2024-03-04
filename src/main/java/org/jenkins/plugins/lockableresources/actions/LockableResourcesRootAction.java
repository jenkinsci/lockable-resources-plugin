/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.actions;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Api;
import hudson.model.RootAction;
import hudson.model.Run;
import hudson.security.AccessDeniedException3;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.Messages;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkins.plugins.lockableresources.queue.QueuedContextStruct;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
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
            Messages.LockableResourcesRootAction_UnlockPermission(),
            Messages._LockableResourcesRootAction_UnlockPermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);
    public static final Permission RESERVE = new Permission(
            PERMISSIONS_GROUP,
            Messages.LockableResourcesRootAction_ReservePermission(),
            Messages._LockableResourcesRootAction_ReservePermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);
    public static final Permission STEAL = new Permission(
            PERMISSIONS_GROUP,
            Messages.LockableResourcesRootAction_StealPermission(),
            Messages._LockableResourcesRootAction_StealPermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);
    public static final Permission VIEW = new Permission(
            PERMISSIONS_GROUP,
            Messages.LockableResourcesRootAction_ViewPermission(),
            Messages._LockableResourcesRootAction_ViewPermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);

    public static final String ICON = "symbol-lock-closed";

    @Override
    public String getIconFileName() {
        return Jenkins.get().hasPermission(VIEW) ? ICON : null;
    }

    public Api getApi() {
        return new Api(this);
    }

    @CheckForNull
    public String getUserName() {
        return LockableResource.getUserName();
    }

    @Override
    public String getDisplayName() {
        return Messages.LockableResourcesRootAction_PermissionGroup();
    }

    @Override
    public String getUrlName() {
        return Jenkins.get().hasPermission(VIEW) ? "lockable-resources" : "";
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
    public Queue getQueue() {
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
        public void add(final LockableResourcesStruct resourceStruct, final QueuedContextStruct context) {
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

            public QueueStruct(final LockableResourcesStruct resourceStruct, final QueuedContextStruct context) {
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
                return this.requiredNumber == null ? "N/A" : this.requiredNumber;
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
    public void doUnlock(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(UNLOCK);

        List<LockableResource> resources = this.getResourcesFromRequest(req, rsp);
        if (resources == null) {
            return;
        }

        LockableResourcesManager.get().unlock(resources, null);

        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doReserve(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(RESERVE);

        List<LockableResource> resources = this.getResourcesFromRequest(req, rsp);
        if (resources == null) {
            return;
        }

        String userName = getUserName();
        if (userName != null) {
            if (!LockableResourcesManager.get().reserve(resources, userName)) {
                rsp.sendError(
                        423,
                        Messages.error_resourceAlreadyLocked(LockableResourcesManager.getResourcesNames(resources)));
                return;
            }
        }
        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doSteal(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(STEAL);

        List<LockableResource> resources = this.getResourcesFromRequest(req, rsp);
        if (resources == null) {
            return;
        }

        String userName = getUserName();
        if (userName != null) {
            LockableResourcesManager.get().steal(resources, userName);
        }

        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doReassign(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
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
    public void doUnreserve(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
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
    public void doReset(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
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
    public void doSaveNote(final StaplerRequest req, final StaplerResponse rsp) throws IOException, ServletException {
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
    public void doChangeQueueOrder(final StaplerRequest req, final StaplerResponse rsp)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(UNLOCK);

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

        if (LockableResourcesManager.get().changeQueueOrder(queueId, newIndex - 1) == false) {
            rsp.sendError(423, Messages.error_invalidIndex(newIndexStr));
            return;
        }

        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    private List<LockableResource> getResourcesFromRequest(final StaplerRequest req, final StaplerResponse rsp)
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
