/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.resources;

import hudson.Extension;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import hudson.widgets.Widget;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.jenkins.plugins.lockableresources.BackwardCompatibility;
import org.jenkins.plugins.lockableresources.LockableResourcesWidget;
import org.jenkins.plugins.lockableresources.SmartSerializableSet;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;
import org.jenkins.plugins.lockableresources.actions.ResourceVariableNameAction;
import org.jenkins.plugins.lockableresources.queue.LockQueueWidget;
import org.jenkins.plugins.lockableresources.queue.context.QueueContext;
import org.jenkins.plugins.lockableresources.queue.policy.QueueFifoPolicy;
import org.jenkins.plugins.lockableresources.queue.policy.QueuePolicy;
import org.jenkins.plugins.lockableresources.resources.selector.ResourcesDefaultSelector;
import org.jenkins.plugins.lockableresources.resources.selector.ResourcesSelector;
import org.jenkins.plugins.lockableresources.step.LockStep;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

@ThreadSafe
@Extension
public class LockableResourcesManager extends GlobalConfiguration {
    private static final Logger LOGGER = Logger.getLogger(LockableResourcesManager.class.getName());
    @Exported
    protected Set<LockableResource> resources = new LinkedHashSet<>();
    @Exported
    protected ResourcesSelector resourcesSelector = new ResourcesDefaultSelector();
    @Exported
    protected QueuePolicy queuePolicy = new QueueFifoPolicy();
    /**
     * Only used when this lockable resource is tried to be locked by {@link LockStep},
     * otherwise (freestyle builds) regular Jenkins queue is used.
     */
    @Exported
    private final SmartSerializableSet<QueueContext> queuedContexts = new SmartSerializableSet<>();
    @Exported
    protected Double defaultReservationHours = 12.0; //hours
    @Exported
    protected Double maxReservationHours = 72.0; //hours
    /** Show widget with resources status in Jenkins main view (below executors list) */
    @Exported
    protected Boolean showWidget = true;
    @Exported
    protected Boolean showQueue = true;

    @DataBoundConstructor
    @SuppressWarnings("OverridableMethodCallInConstructor")
    public LockableResourcesManager() {
        load();
        Timer.get().schedule(new Runnable() {
            @Override
            public void run() {
                // initiate lock management
                retryLock();
            }
        }, 5, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void load() {
        LOGGER.fine("Loading from file " + getConfigFile());
        super.load();
        setShowWidget(showWidget);
        setShowQueue(showQueue);
    }

    /**
     * Backward compatibility
     */
    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void initBackwardCompatibility() {
        BackwardCompatibility.init();
    }

    /**
     * For backward compatibility
     *
     * @return
     */
    @Override
    protected XmlFile getConfigFile() {
        File oldFile = new File(Jenkins.getInstance().getRootDir(), "org.jenkins.plugins.lockableresources.LockableResourcesManager.xml");
        if(oldFile.exists()) {
            return new XmlFile(Jenkins.XSTREAM2, oldFile);
        }
        return new XmlFile(Jenkins.XSTREAM2, oldFile);
    }

    @Exported
    public synchronized Set<LockableResource> getResources() {
        return Collections.unmodifiableSet(resources);
    }

    @DataBoundSetter
    public synchronized void setResources(Set<LockableResource> resources) {
        this.resources.clear();
        this.resources.addAll(resources);
    }

    @Exported
    public synchronized ResourcesSelector getResourcesSelector() {
        return resourcesSelector;
    }

    @DataBoundSetter
    public synchronized void setResourcesSelector(ResourcesSelector resourcesSelector) {
        this.resourcesSelector = resourcesSelector;
    }

    @Exported
    public synchronized QueuePolicy getQueuePolicy() {
        return queuePolicy;
    }

    @DataBoundSetter
    public synchronized void setQueuePolicy(QueuePolicy queuePolicy) {
        this.queuePolicy = queuePolicy;
    }

    @Exported
    public synchronized Double getDefaultReservationHours() {
        return defaultReservationHours;
    }

    @DataBoundSetter
    public synchronized void setDefaultReservationHours(Double defaultReservationHours) {
        this.defaultReservationHours = defaultReservationHours;
    }

    @Exported
    public synchronized Double getMaxReservationHours() {
        return maxReservationHours;
    }

    @DataBoundSetter
    public synchronized void setMaxReservationHours(Double maxReservationHours) {
        this.maxReservationHours = maxReservationHours;
    }

    @Exported
    public synchronized Boolean getShowWidget() {
        return showWidget;
    }

    @DataBoundSetter
    public synchronized void setShowWidget(Boolean showWidget) {
        this.showWidget = showWidget;
        updateWidgetVisibility();
    }

    @Exported
    public synchronized Boolean getShowQueue() {
        return showQueue;
    }

    @DataBoundSetter
    public synchronized void setShowQueue(Boolean showQueue) {
        this.showQueue = showQueue;
        updateWidgetVisibility();
    }

    private void updateWidgetVisibility() {
        List<Widget> widgets = Jenkins.getInstance().getWidgets();
        LockableResourcesWidget listAlreadyVisible = null;
        LockQueueWidget queueAlreadyVisible = null;
        for(Widget widget : widgets) {
            if(widget instanceof LockableResourcesWidget) {
                listAlreadyVisible = (LockableResourcesWidget) widget;
            }
            if(widget instanceof LockQueueWidget) {
                queueAlreadyVisible = (LockQueueWidget) widget;
            }
        }
        if(showWidget) {
            if(listAlreadyVisible == null) {
                widgets.add(new LockableResourcesWidget());
            }
        } else if(listAlreadyVisible != null) {
            widgets.remove(listAlreadyVisible);
        }
        if(showQueue) {
            if(queueAlreadyVisible == null) {
                widgets.add(new LockQueueWidget());
            }
        } else if(queueAlreadyVisible != null) {
            widgets.remove(queueAlreadyVisible);
        }
    }

    public synchronized Set<LockableResource> getAllResources() {
        return Collections.unmodifiableSet(resources);
    }

    @Override
    public String getDisplayName() {
        return "External Resources";
    }

    public synchronized int getFreeAmount(String labels) {
        Set<LockableResource> res = ResourceCapability.getResourcesFromCapabilities(resources, ResourceCapability.splitCapabilities(labels), null, null);
        int nb = 0;
        for(LockableResource r : res) {
            if(r.canLock(null, 0)) {
                nb++;
            }
        }
        return nb;
    }

    public synchronized Set<String> getAllResourceNames() {
        HashSet<String> res = new HashSet<>(resources.size());
        for(LockableResource r : resources) {
            res.add(r.getName());
        }
        return res;
    }

    @CheckForNull
    public synchronized Set<LockableResource> getResourcesFromNames(@Nonnull Collection<String> resourceNames) {
        LinkedHashSet<LockableResource> res = new LinkedHashSet<>(); // keep same ordering as input
        if(resourceNames.isEmpty()) {
            return res;
        }
        LinkedHashSet<String> buffer = new LinkedHashSet<>(resourceNames); // constant remove time
        for(LockableResource r : resources) {
            String name = r.getName();
            if(buffer.contains(name)) {
                res.add(r);
                buffer.remove(name);
                if(buffer.isEmpty()) {
                    return res;
                }
            }
        }
        LOGGER.info("Unknown resources names: " + buffer);
        return null; // At least one resource name is unknown
    }

    public synchronized LockableResource getResourceFromName(String resourceName) {
        if(resourceName != null) {
            for(LockableResource resource : resources) {
                if(resourceName.equals(resource.getName())) {
                    return resource;
                }
            }
        }
        return null;
    }

    public static List<String> getResourcesNames(@Nonnull Collection<LockableResource> resources) {
        // since LockableResource contains transient variables, they cannot be correctly serialized
        // hence we use their unique resource names
        ArrayList<String> resourceNames = new ArrayList<>();
        for(LockableResource resource : resources) {
            resourceNames.add(resource.getName());
        }
        // Sort names for predictable logs (for tests)
        Collections.sort(resourceNames);
        return resourceNames;
    }

    public synchronized Set<String> getInvalidResourceNames(@Nonnull Collection<String> resourceNames) {
        LinkedHashSet<String> buffer = new LinkedHashSet<>(resourceNames);
        for(LockableResource r : resources) {
            String name = r.getName();
            if(buffer.contains(name)) {
                buffer.remove(name);
                if(buffer.isEmpty()) {
                    return buffer;
                }
            }
        }
        return buffer; // At least one resource name is unknown
    }

    public synchronized Set<LockableResource> getQueuedResourcesFromProject(String projectFullName) {
        Set<LockableResource> matching = new HashSet<>();
        for(LockableResource r : resources) {
            String queueItemProject = r.getQueueItemProject();
            if((queueItemProject != null) && queueItemProject.equals(projectFullName)) {
                matching.add(r);
            }
        }
        return matching;
    }

    // Adds already selected (in previous queue round) resources to 'selected'
    private synchronized Set<LockableResource> getQueuedResources(long queueId) {
        Set<LockableResource> res = new HashSet<>();
        for(LockableResource resource : resources) {
            // This project might already have something in queue
            String rProject = resource.getQueueItemProject();
            if((rProject != null) && resource.isQueuedByTask(queueId)) {
                // this item has queued the resource earlier
                res.add(resource);
            }
        }
        return res;
    }

    public synchronized Set<LockableResource> getLockedResourcesFromBuild(Run<?, ?> build) {
        Set<LockableResource> matching = new HashSet<>();
        for(LockableResource r : resources) {
            Run<?, ?> rBuild = r.getBuild();
            if((rBuild != null) && (rBuild == build)) {
                matching.add(r);
            }
        }
        return matching;
    }

    public synchronized Boolean isValidLabel(String singleLabel, boolean acceptResourceName) {
        return singleLabel.startsWith("$") || this.getAllLabels(acceptResourceName).contains(singleLabel);
    }

    public synchronized Set<String> getAllLabels(boolean withResourceNames) {
        TreeSet<String> labels = new TreeSet<>();
        for(LockableResource r : this.resources) {
            Set<String> r_labels = Utils.splitLabels(r.getLabels());
            labels.addAll(r_labels);
            if(withResourceNames) {
                labels.add(r.getName());
            }
        }
        return labels;
    }

    /**
     * Queue lockable resources for later lock
     * Get already queued item from the same project and build and try to complete the selection
     * If all required resources are not available, then previously queued items are released
     * <p>
     * Requires a queue item (for example with a build of a standard freestyle project)
     *
     * @param project
     * @param queueContext
     *
     * @return Return the list of queued resources, or null if no enough resources
     */
    public synchronized boolean tryQueue(@Nonnull Job<?, ?> project, @Nonnull QueueContext queueContext) {
        queuedContexts.add(queueContext);
        save();

        final String projectFullName = project.getFullName();

        Collection<RequiredResources> requiredResourcesList = queueContext.getRequiredResources();
        LOGGER.finest(projectFullName + " trying to get resources with these details: " + requiredResourcesList);
        Set<LockableResource> selected = resourcesSelector.selectFreeResources(resources, queueContext);

        if(selected != null) {
            // Resources for this build have been selected: queue all associated resources
            final long queueId = queueContext.getQueueId();
            for(LockableResource r : selected) {
                r.setQueued(queueId, projectFullName);
            }
            queuedContexts.remove(queueContext);
            save();
            return true;
        }
        return false;
    }

    /**
     * Try to lock required resources.<br>
     * If not possible, put context in queue for next try
     *
     * @param queueContext
     *
     * @return Return true if resources has been locked, or false if no enough resources
     */
    public synchronized boolean lockNowOrLater(@Nonnull QueueContext queueContext) {
        queuedContexts.add(queueContext);
        save();
        return retryLock().contains(queueContext);
    }

    /**
     * Try to lock required resources.<br>
     * If not possible, do nothing
     *
     * @param queueContext
     *
     * @return Return true if resources has been locked, or false if no enough resources
     */
    public synchronized boolean lockNowOrNever(@Nonnull QueueContext queueContext) {
        Set<LockableResource> resourcesForNextContext = resourcesSelector.selectFreeResources(resources, queueContext);
        if(resourcesForNextContext == null) {
            // No enough resources
            return false;
        }
        return lock(resourcesForNextContext, queueContext);
    }

    /**
     * Try to lock the resource and return true if locked.
     *
     * @param resources
     * @param build
     * @param context
     * @param requiredresources
     * @param variableName
     *
     * @return
     */
    private synchronized boolean lock(@Nullable Set<LockableResource> resources, QueueContext queueContext) {
        if(resources == null) {
            return false;
        }
        Run<?, ?> build = queueContext.getBuild();
        if(build == null) {
            return false;
        }
        boolean removed = queuedContexts.remove(queueContext); // Remove context to prevent another possible lock call in sub-functions
        String userId = queueContext.getUserId();
        for(LockableResource r : resources) {
            if(!r.canLock(userId, queueContext.getQueueId())) {
                // At least one resource is not available: abort lock process
                if(removed) {
                    queuedContexts.add(queueContext);
                }
                return false;
            }
        }
        
        LOGGER.info("Lock resources " + resources + " by " + build.getExternalizableId());
        for(LockableResource r : resources) {
            r.unqueue();
            r.setBuild(build);
        }
        save();
        
        TaskListener listener = queueContext.getListener();
        if(listener != null) {
            listener.getLogger().println("Lock resources " + getResourcesNames(resources));
            listener.getLogger().println("Lock acquired on " + queueContext.getRequiredResources());
        }
        String variableName = queueContext.getVariableName();
        if(variableName != null) {
            String lbl = getParameterValue(resources);
            build.addAction(new ResourceVariableNameAction(new StringParameterValue(variableName, lbl)));
        }
        LockedResourcesBuildAction action = build.getAction(LockedResourcesBuildAction.class);
        if(action == null) {
            build.addAction(new LockedResourcesBuildAction(resources));
        } else {
            action.addLockedResources(resources);
        }
        queueContext.proceed(resources);
        return true;
    }

    @Nonnull
    private static String getParameterValue(@Nonnull Collection<LockableResource> resources) {
        ArrayList<String> resourcesNames = new ArrayList<>(resources.size());
        for(LockableResource r : resources) {
            resourcesNames.add(r.getName());
        }
        Collections.sort(resourcesNames);
        StringBuilder lbl = new StringBuilder();
        for(String name : resourcesNames) {
            if(lbl.length() > 0) {
                lbl.append(", ");
            }
            lbl.append(name);
        }
        return lbl.toString();
    }

    public synchronized void unlock(@Nonnull Collection<LockableResource> resourcesToUnLock) {
        unlock(resourcesToUnLock, null, null);
    }

    public synchronized void unlock(@Nonnull Collection<LockableResource> resourcesToUnLock, @Nullable Run<?, ?> build, @Nullable TaskListener listener) {
        //--------------------------
        // Free old resources no longer needed
        //--------------------------
        if(listener != null) {
            listener.getLogger().printf("Unlock resources " + resourcesToUnLock);
        }
        if(build == null) {
            LOGGER.info("Unlock resources " + resourcesToUnLock);
            for(LockableResource resource : resourcesToUnLock) {
                if((resource != null) && resource.isLocked()) {
                    // No more contexts, unlock resource
                    resource.unqueue();
                    resource.setBuild(null);
                }
            }
        } else {
            LOGGER.info("Unlock resources " + resourcesToUnLock + " by " + build.getExternalizableId());
            for(LockableResource resource : resourcesToUnLock) {
                if((resource != null) && resource.isLockedByBuild(build)) {
                    // No more contexts, unlock resource
                    resource.unqueue();
                    resource.setBuild(null);
                }
            }
        }
        save();

        //--------------------------
        // Check if there are works waiting for resources
        //--------------------------
        retryLock();
    }

    @Nonnull
    public synchronized Set<QueueContext> retryLock() {
        Set<QueueContext> res = new HashSet<>();

        while(true) {
            queueCleanup();
            QueueContext nextStruct = queuePolicy.select(queuedContexts, resources, resourcesSelector);
            if(nextStruct == null) {
                // No context is queued which can be started once these resources are free'd.
                break;
            }
            if(lockNowOrNever(nextStruct)) {
                res.add(nextStruct);
            } else {
                break;
            }
        }
        return res;
    }

    /**
     * Creates the resource if it does not exist.
     *
     * @param name
     *
     * @return
     */
    public synchronized boolean createResource(String name) {
        return createResource(name, null);
    }

    public synchronized boolean createResource(String name, String capabilities) {
        LockableResource existent = getResourceFromName(name);
        if(existent == null) {
            LockableResource resource = new LockableResource(name, capabilities);
            resources.add(resource);
            save();
            return true;
        }
        return false;
    }

    public synchronized void reserve(List<LockableResource> resources, @Nullable String byUser, @Nonnull String comments) {
        reserve(resources, byUser, null, null, comments);
    }

    public synchronized void reserve(@Nonnull List<LockableResource> resources, @Nullable String byUser, @Nullable String forUser, @Nullable Double hours, @Nonnull String comments) {
        double maxHours = getMaxReservationHours();
        Double realHours;
        if((hours != null) && (maxHours > 0)) {
            realHours = Math.min(hours, maxHours);
        } else if(getDefaultReservationHours() <= 0) {
            realHours = null;
        } else {
            realHours = hours;
        }
        for(LockableResource resource : resources) {
            resource.reserveFor(byUser, forUser, realHours, comments);
        }
        save();

        //--------------------------
        // Check if there are works waiting for resources
        //--------------------------
        retryLock();
    }

    public synchronized void unqueue(QueueContext queueContext) {
        Collection<LockableResource> toUnqueue = getQueuedResources(queueContext.getQueueId());
        for(LockableResource resource : toUnqueue) {
            resource.unqueue();
        }
        save();

        //--------------------------
        // Check if there are works waiting for resources
        //--------------------------
        retryLock();
    }

    public synchronized void unreserve(Collection<LockableResource> resources) {
        for(LockableResource resource : resources) {
            resource.unReserve();
        }
        save();

        //--------------------------
        // Check if there are works waiting for resources
        //--------------------------
        retryLock();
    }

    public synchronized void reset(Collection<LockableResource> resources) {
        for(LockableResource resource : resources) {
            resource.reset();
        }
        save();

        //--------------------------
        // Check if there are works waiting for resources
        //--------------------------
        retryLock();
    }

    @Override
    public synchronized boolean configure(StaplerRequest req, JSONObject json) throws FormException {
        LOGGER.fine("Entering manager.configure()");
        try {
            List<LockableResource> newResources = req.bindJSONToList(LockableResource.class, json.get("resources"));
            for(LockableResource resource : newResources) {
                // Keep current queue/lock/reserved/offline status without modification
                LockableResource oldResource = getResourceFromName(resource.getName());
                if(oldResource != null) {
                    resource.setBuild(oldResource.getBuild());
                    resource.setQueued(oldResource.getQueueItemId(), oldResource.getQueueItemProject());
                    resource.setReservedBy(oldResource.getReservedBy());
                    resource.setReservedFor(oldResource.getReservedFor());
                    resource.setReservedUntil(oldResource.getReservedUntil());
                }
            }
            this.resources.clear();
            this.resources.addAll(newResources);
        } catch(JSONException e) {
            LOGGER.log(Level.SEVERE, "manager.configure()", e);
            return false;
        }
        try {
            this.defaultReservationHours = json.getDouble("defaultReservationHours");
        } catch(JSONException e) {
            LOGGER.log(Level.SEVERE, "manager.configure()", e);
            this.defaultReservationHours = 12.0; //backward compatibility
        }
        try {
            this.maxReservationHours = json.getDouble("maxReservationHours");
        } catch(JSONException e) {
            LOGGER.log(Level.SEVERE, "manager.configure()", e);
            this.maxReservationHours = 72.0; //backward compatibility
        }
        try {
            setShowWidget(json.getBoolean("showWidget"));
        } catch(JSONException e) {
            LOGGER.log(Level.SEVERE, "manager.configure()", e);
            setShowWidget(true); //backward compatibility
        }
        try {
            setShowQueue(json.getBoolean("showQueue"));
        } catch(JSONException e) {
            LOGGER.log(Level.SEVERE, "manager.configure()", e);
            setShowQueue(true); //backward compatibility
        }
        try {
            this.resourcesSelector = req.bindJSON(ResourcesSelector.class, json.getJSONObject("resourcesSelector"));
        } catch(JSONException e) {
            LOGGER.log(Level.SEVERE, "manager.configure()", e);
            this.resourcesSelector = new ResourcesDefaultSelector();
        }
        try {
            this.queuePolicy = req.bindJSON(QueuePolicy.class, json.getJSONObject("queuePolicy"));
        } catch(JSONException e) {
            LOGGER.log(Level.SEVERE, "manager.configure()", e);
            this.queuePolicy = new QueueFifoPolicy();
        }
        save();
        return true;
    }

    private synchronized void queueCleanup() {
        for(Iterator<QueueContext> iter = this.queuedContexts.iterator(); iter.hasNext();) {
            QueueContext queueContext = iter.next();
            if(!queueContext.isStillApplicable()) {
                LOGGER.info("Item in resources queue is no more applicable: " + queueContext);
                iter.remove();
                save();
                queueContext.cancel();
            }
        }
    }

    public synchronized List<QueueContext> getQueue() {
        queueCleanup();
        return queuePolicy.sort(queuedContexts);
    }

    public synchronized boolean removeFromLockQueue(Queue.Item item) {
        for(Iterator<QueueContext> iter = this.queuedContexts.iterator(); iter.hasNext();) {
            QueueContext entry = iter.next();
            if(entry.getQueueId() == item.getId()) {
                iter.remove();
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized boolean removeFromLockQueue(Run<?, ?> build) {
        for(Iterator<QueueContext> iter = this.queuedContexts.iterator(); iter.hasNext();) {
            QueueContext entry = iter.next();
            if((entry.getBuild() == build) || (entry.getQueueId() == build.getQueueId())) {
                iter.remove();
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized boolean removeFromLockQueue(StepContext context) {
        try {
            Run<?, ?> build = context.get(Run.class);
            if(build != null) {
                for(Iterator<QueueContext> iter = this.queuedContexts.iterator(); iter.hasNext();) {
                    QueueContext entry = iter.next();
                    if((entry.getBuild() == build) || (entry.getQueueId() == build.getQueueId())) {
                        iter.remove();
                        save();
                        return true;
                    }
                }
            }
        } catch(IOException | InterruptedException ex) {
        }
        return false;
    }

    public static LockableResourcesManager get() {
        Jenkins jenkins = Jenkins.getInstance();
        return (LockableResourcesManager) jenkins.getDescriptorOrDie(LockableResourcesManager.class);
    }
}
