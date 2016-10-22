/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.resources;

import groovy.lang.Tuple2;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.matrix.MatrixConfiguration;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
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
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.codehaus.groovy.runtime.AbstractComparator;
import org.jenkins.plugins.lockableresources.BackwardCompatibility;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.jobProperty.RequiredResourcesProperty;
import org.jenkins.plugins.lockableresources.queue.QueuedContextStruct;
import org.jenkins.plugins.lockableresources.step.LockStep;
import org.jenkins.plugins.lockableresources.step.LockStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

@Extension
public class LockableResourcesManager extends GlobalConfiguration {
    private static final Logger LOGGER = Logger.getLogger(LockableResourcesManager.class.getName());
    @Exported
    protected Set<LockableResource> resources = new LinkedHashSet<>();
    /** If this option is selected, the plugin will use an internal algorithm to select
     * the free resources based on their capabilities.<br>
     * The resource that has a unique capability among all other resources has less chance
     * to be selected.<br>
     * On the contrary, if a free resource has very common capabilities it will probably be selected
     * <p>
     * This option is highly experimental.
     */
    @Exported
    protected Boolean useFairSelection = false;
    /**
     * Only used when this lockable resource is tried to be locked by {@link LockStep},
     * otherwise (freestyle builds) regular Jenkins queue is used.
     */
    private final List<QueuedContextStruct> queuedContexts = new ArrayList<>();

    @DataBoundConstructor
    public LockableResourcesManager() {
        load();
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

    @Override
    public synchronized void save() {
        super.save(); //To change body of generated methods, choose Tools | Templates.
    }

    @Exported
    public Set<LockableResource> getResources() {
        return resources;
    }

    @DataBoundSetter
    public void setResources(Set<LockableResource> resources) {
        this.resources = resources;
    }

    @Exported
    public Boolean getUseFairSelection() {
        return useFairSelection;
    }

    @DataBoundSetter
    public void setUseFairSelection(Boolean useFairSelection) {
        this.useFairSelection = useFairSelection;
    }

    public Set<LockableResource> getAllResources() {
        return resources;
    }

    @Override
    public String getDisplayName() {
        return "External Resources";
    }

    public Set<LockableResource> filterFreeResources(Collection<LockableResource> resources) {
        HashSet<LockableResource> free = new HashSet<>();
        for(LockableResource r : resources) {
            if(r.isFree()) {
                free.add(r);
            }
        }
        return free;
    }

    public int getFreeAmount(Collection<LockableResource> resources) {
        return filterFreeResources(resources).size();
    }

    public int getFreeAmount(String labels, @Nullable EnvVars env) {
        Set<LockableResource> res = getResourcesFromCapabilities(ResourceCapability.splitCapabilities(labels), null, env);
        return filterFreeResources(res).size();
    }

    public Set<String> getAllResourceNames() {
        HashSet<String> res = new HashSet<>(resources.size());
        for(LockableResource r : resources) {
            res.add(r.getName());
        }
        return res;
    }

    public Set<LockableResource> getResourcesFromNames(@Nonnull Collection<String> resourceNames) {
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

    public LockableResource getResourceFromName(String resourceName) {
        if(resourceName != null) {
            for(LockableResource resource : resources) {
                if(resourceName.equals(resource.getName())) {
                    return resource;
                }
            }
        }
        return null;
    }

    public Set<String> getInvalidResourceNames(@Nonnull Collection<String> resourceNames) {
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

    public Set<LockableResource> getQueuedResourcesFromProject(String projectFullName) {
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
    private Set<LockableResource> getQueuedResources(String projectFullName, long taskId) {
        Set<LockableResource> res = new HashSet<>();
        for(LockableResource resource : resources) {
            // This project might already have something in queue
            String rProject = resource.getQueueItemProject();
            if((rProject != null) && rProject.equals(projectFullName) && resource.isQueuedByTask(taskId)) {
                // this item has queued the resource earlier
                res.add(resource);
            }
        }
        return res;
    }

    public Set<LockableResource> getLockedResourcesFromBuild(Run<?, ?> build) {
        Set<LockableResource> matching = new HashSet<>();
        for(LockableResource r : resources) {
            Run<?, ?> rBuild = r.getBuild();
            if((rBuild != null) && (rBuild == build)) {
                matching.add(r);
            }
        }
        return matching;
    }

    public Boolean isValidLabel(String singleLabel, boolean acceptResourceName) {
        return singleLabel.startsWith("$") || this.getAllLabels(acceptResourceName).contains(singleLabel);
    }

    public Set<String> getAllLabels(boolean withResourceNames) {
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

    public Set<ResourceCapability> getAllCapabilities() {
        HashSet<ResourceCapability> capabilities = new HashSet<>();
        for(LockableResource r : this.resources) {
            capabilities.addAll(r.getCapabilities());
            capabilities.add(r.getMyselfAsCapability());
        }
        return capabilities;
    }

    /**
     *
     * @param neededCapabilities
     * @param prohibitedCapabilities
     * @param env                    Used only for Groovy script execution
     *
     * @return
     */
    public Set<ResourceCapability> getCompatibleCapabilities(Collection<ResourceCapability> neededCapabilities, Collection<ResourceCapability> prohibitedCapabilities, @Nullable EnvVars env) {
        TreeSet<ResourceCapability> capabilities = new TreeSet();
        for(LockableResource r : this.resources) {
            if(r.hasCapabilities(neededCapabilities, prohibitedCapabilities, env)) {
                capabilities.addAll(r.getCapabilities());
                capabilities.add(r.getMyselfAsCapability());
            }
        }
        return capabilities;
    }

    /**
     *
     * @param neededCapabilities
     * @param prohibitedCapabilities
     * @param env                    Used only for Groovy script execution
     *
     * @return
     */
    public Set<LockableResource> getResourcesFromCapabilities(@Nullable Collection<ResourceCapability> neededCapabilities, @Nullable Collection<ResourceCapability> prohibitedCapabilities, @Nullable EnvVars env) {
        HashSet<LockableResource> found = new HashSet<>();
        for(LockableResource r : this.resources) {
            if(r.hasCapabilities(neededCapabilities, prohibitedCapabilities, env)) {
                found.add(r);
            }
        }
        return found;
    }

    public Collection<RequiredResources> getProjectRequiredResources(Job<?, ?> project) {
        if(project instanceof MatrixConfiguration) {
            project = (Job<?, ?>) project.getParent();
        }
        RequiredResourcesProperty property = project.getProperty(RequiredResourcesProperty.class);
        if(property == null) {
            return Collections.emptyList();
        }
        return property.getRequiredResourcesList();
    }

    public Set<LockableResource> selectFreeResources(Collection<RequiredResources> requiredResourcesList, Collection<LockableResource> forcedCandidates, EnvVars env) {
        List<Tuple2<Set<LockableResource>, Integer>> request = new ArrayList<>(requiredResourcesList.size());
        Set<LockableResource> res = new HashSet<>();
        for(RequiredResources rr : requiredResourcesList) {
            Set<LockableResource> candidates;
            if(Util.fixEmpty(rr.getExpandedResources(env)) == null) {
                // Use labels => convert them to capabilities
                Set<ResourceCapability> capabilities = rr.getCapabilitiesList(env);
                candidates = getResourcesFromCapabilities(capabilities, null, env);
            } else {
                candidates = rr.getResourcesList(env);
            }
            Set<LockableResource> freeCandidates = filterFreeResources(candidates);
            if(forcedCandidates != null) {
                freeCandidates.addAll(forcedCandidates);
            }
            if(rr.quantity <= 0) {
                if(freeCandidates.size() == candidates.size()) {
                    // Use all resources of this type
                    res.addAll(freeCandidates);
                } else {
                    // Atleast one resource is locked/queued: exit
                    return null;
                }
            } else {
                if(useFairSelection) {
                    freeCandidates = sortResources(freeCandidates, env);
                }
                request.add(new Tuple2<>(freeCandidates, rr.quantity));
            }
        }
        Set<LockableResource> selected = selectAmongPossibilities(request);
        if(selected == null) {
            // No enough free resources
            return null;
        }
        res.addAll(selected);
        return res;
    }

    public LinkedHashSet<LockableResource> sortResources(Collection<LockableResource> resources, EnvVars env) {
        // Extract the best resources based on their capabilities
        List<Tuple2<LockableResource, Double>> sortedFree = new ArrayList<>(); // (resource, cost)
        for(LockableResource r : resources) {
            Set<ResourceCapability> rc = r.getCapabilities();
            Set<LockableResource> compatibles = getResourcesFromCapabilities(rc, null, env);
            int nFree = getFreeAmount(compatibles);
            int nMax = compatibles.size();
            double cost = (nMax - nFree) * (resources.size() / nMax) + rc.size();
            sortedFree.add(new Tuple2<>(r, cost));
        }
        Collections.sort(sortedFree, new AbstractComparator<Tuple2<LockableResource, Double>>() {
            @Override
            public int compare(Tuple2<LockableResource, Double> o1, Tuple2<LockableResource, Double> o2) {
                return o1.getSecond().compareTo(o2.getSecond());
            }
        });
        LOGGER.finer("Costs for using resources:");
        LinkedHashSet<LockableResource> res = new LinkedHashSet();
        for(Tuple2<LockableResource, Double> t : sortedFree) {
            res.add(t.getFirst());
            LOGGER.info(" - " + t.getFirst().getName() + ": " + t.getSecond());
        }
        return res;
    }

    /**
     * Try to lock the resource and return true if locked.
     *
     * @param resources
     * @param build
     * @param context
     * @param requiredresources
     * @param inversePrecedence
     *
     * @return
     */
    public synchronized boolean lock(Collection<LockableResource> resources,
            Collection<RequiredResources> requiredresources,
            Run<?, ?> build, @Nullable StepContext context,
            boolean inversePrecedence) {
        if(resources == null) {
            return false;
        }
        for(LockableResource r : resources) {
            if(!r.canLock()) {
                // At least one resource is not available: abort lock process
                save();
                return false;
            }
        }
        for(LockableResource r : resources) {
            r.unqueue();
            r.setBuild(build);
        }
        if(context != null) {
            // since LockableResource contains transient variables, they cannot be correctly serialized
            // hence we use their unique resource names
            List<String> resourceNames = new ArrayList<>();
            for(LockableResource resource : resources) {
                resourceNames.add(resource.getName());
            }
            LockStepExecution.proceed(resourceNames, requiredresources, context, inversePrecedence);
        }
        save();
        return true;
    }

    private synchronized void unlockResources(Collection<LockableResource> unlockResources, Run<?, ?> build) {
        for(LockableResource resource : unlockResources) {
            if(resource == null || (resource.getBuild() != null && build.getExternalizableId().equals(resource.getBuild().getExternalizableId()))) {
                // No more contexts, unlock resource
                resource.unqueue();
                resource.setBuild(null);
            }
        }
    }

    public synchronized void unlock(Collection<LockableResource> resourcesToUnLock,
            Run<?, ?> build, @Nullable StepContext context) {
        unlock(resourcesToUnLock, build, context, false);
    }

    public synchronized void unlock(@Nullable Collection<LockableResource> resourcesToUnLock,
            Run<?, ?> build, @Nullable StepContext context,
            boolean inversePrecedence) {
        // check if there are resources which can be unlocked (and shall not be unlocked)
        QueuedContextStruct nextContext = getNextQueuedContext(resourcesToUnLock, inversePrecedence);
        // no context is queued which can be started once these resources are free'd.
        if(nextContext == null) {
            unlockResources(resourcesToUnLock, build);
            save();
            return;
        }
        // resourcesToUnLock: contains the previous resources (still locked).
        // requiredResourceForNextContext: contains the resource objects which are required for the next context.
        // It is guaranteed that there is an overlap between the two - the resources which are to be reused.
        EnvVars env = Utils.getEnvVars(context);
        Set<LockableResource> requiredResourceForNextContext = selectFreeResources(nextContext.getRequiredResourcesList(), resourcesToUnLock, env);
        if(requiredResourceForNextContext != null) {
            // remove context from queue and process it
            this.queuedContexts.remove(nextContext);
            // lock all (old and new resources)
            for(LockableResource requiredResource : requiredResourceForNextContext) {
                try {
                    requiredResource.setBuild(nextContext.getContext().get(Run.class));
                } catch(IOException | InterruptedException e) {
                    throw new IllegalStateException("Can not access the context of a running build", e);
                }
            }
            // determine old resources no longer needed
            List<LockableResource> freeResources = new ArrayList<>();
            for(LockableResource resourceToUnlock : resourcesToUnLock) {
                if(!requiredResourceForNextContext.contains(resourceToUnlock)) {
                    freeResources.add(resourceToUnlock);
                }
            }
            // free old resources no longer needed
            unlockResources(freeResources, build);
            // continue with next context
            List<String> resourceNames = new ArrayList<>();
            for(LockableResource resource : requiredResourceForNextContext) {
                resourceNames.add(resource.getName());
            }
            LockStepExecution.proceed(resourceNames, nextContext.getRequiredResourcesList(), nextContext.getContext(), inversePrecedence);
        }
        save();
    }

    private QueuedContextStruct getNextQueuedContext(Collection<LockableResource> resourceToUnLock, boolean inversePrecedence) {
        if(this.queuedContexts == null) {
            return null;
        }
        QueuedContextStruct newestEntry = null;
        if(!inversePrecedence) {
            for(QueuedContextStruct entry : this.queuedContexts) {
                EnvVars env = Utils.getEnvVars(entry.getContext());
                if(selectFreeResources(entry.getRequiredResourcesList(), resourceToUnLock, env) != null) {
                    return entry;
                }
            }
        } else {
            long newest = 0;
            for(QueuedContextStruct entry : this.queuedContexts) {
                EnvVars env = Utils.getEnvVars(entry.getContext());
                if(selectFreeResources(entry.getRequiredResourcesList(), resourceToUnLock, env) != null) {
                    try {
                        Run<?, ?> run = entry.getContext().get(Run.class);
                        if(run.getStartTimeInMillis() > newest) {
                            newest = run.getStartTimeInMillis();
                            newestEntry = entry;
                        }
                    } catch(IOException | InterruptedException e) {
                        // skip this one
                    }
                }
            }
        }
        return newestEntry;
    }

    /**
     * Creates the resource if it does not exist.
     *
     * @param name
     *
     * @return
     */
    public synchronized boolean createResource(String name) {
        LockableResource existent = getResourceFromName(name);
        if(existent == null) {
            LockableResource resource = new LockableResource(name);
            resources.add(resource);
            save();
            return true;
        }
        return false;
    }

    public synchronized boolean createResourceWithLabel(String name, String labels) {
        LockableResource existent = getResourceFromName(name);
        if(existent == null) {
            resources.add(new LockableResource(name, labels));
            save();
            return true;
        }
        return false;
    }

    public synchronized boolean reserve(List<LockableResource> resources, String userName) {
        for(LockableResource resource : resources) {
            if(!resource.isFree()) {
                return false;
            }
        }
        for(LockableResource resource : resources) {
            resource.setReservedBy(userName);
        }
        save();
        return true;
    }

    public synchronized void unreserve(List<LockableResource> resources) {
        for(LockableResource resource : resources) {
            resource.unReserve();
        }
        save();
    }

    public synchronized void reset(List<LockableResource> resources) {
        for(LockableResource resource : resources) {
            resource.reset();
        }
        save();
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
        try {
            LOGGER.fine("Entering manager.configure()");
            List<LockableResource> newResources = req.bindJSONToList(LockableResource.class, json.get("resources"));
            for(LockableResource resource : newResources) {
                LockableResource oldResource = getResourceFromName(resource.getName());
                if(oldResource != null) {
                    resource.setBuild(oldResource.getBuild());
                    resource.setQueued(resource.getQueueItemId(), resource.getQueueItemProject());
                }
            }
            this.resources.clear();
            this.resources.addAll(newResources);
            this.useFairSelection = json.getBoolean("useFairSelection");
            save();
            return true;
        } catch(JSONException e) {
            LOGGER.log(Level.SEVERE, "manager.configure()", e);
            return false;
        }
    }

    /**
     * Queue lockable resources for later lock
     * Get already queued item from the same project and build and try to complete the selection
     * If all required resources are not available, then previously queued item are released
     * <p>
     * Requires a queue item (for example with a build of a standard freestyle project)
     *
     * @param project
     * @param item
     *
     * @return Return the list of queued resources, or null if no enough resources
     */
    public synchronized Set<LockableResource> queue(Job<?, ?> project, Queue.Item item) {
        final long taskId = item.getId();
        final String projectFullName = project.getFullName();
        final EnvVars env = Utils.getEnvVars(item);

        Collection<RequiredResources> requiredResourcesList = getProjectRequiredResources(project);
        LOGGER.finest(projectFullName + " trying to get resources with these details: " + requiredResourcesList);

        if(!isAnotherBuildWaitingResources(projectFullName, taskId)) {
            // The project has another buildable item waiting -> bail out
            LOGGER.log(Level.FINEST, "{0} has another build waiting resources."
                    + " Waiting for it to proceed first.",
                    new Object[] {projectFullName});
            return null;
        }
        Set<LockableResource> alreadyQueued = getQueuedResources(projectFullName, taskId);

        LOGGER.finest(projectFullName + ": trying to get resources with these details: " + requiredResourcesList);
        Set<LockableResource> selected = new HashSet<>(alreadyQueued);
        selected = selectFreeResources(requiredResourcesList, selected, env);

        if(selected == null) {
            // No enough resources for this build: unqueue all associated resources
            for(LockableResource r : alreadyQueued) {
                r.unqueue();
            }
        } else {
            // Resources for this build have been selected: queue all associated resources
            for(LockableResource r : selected) {
                r.setQueued(taskId, projectFullName);
            }
        }
        return selected;
    }

    // Return false if another item queued for this project -> bail out
    private boolean isAnotherBuildWaitingResources(String projectFullName, long taskId) {
        for(LockableResource resource : resources) {
            // This project might already have something in queue
            String rProject = resource.getQueueItemProject();
            if(rProject != null && rProject.equals(projectFullName)) {
                if(!resource.isQueuedByTask(taskId)) {
                    // The project has another buildable item waiting -> bail out
                    LOGGER.log(Level.FINEST, "{0} has another build "
                            + "that already queued resource {1}. Continue queueing.",
                            new Object[] {projectFullName, resource});
                    return false;
                }
            }
        }
        return true;
    }

    /*
     * Adds the given context and the required resources to the queue if
     * this context is not yet queued.
     */
    public void queueContext(StepContext context, Collection<RequiredResources> requiredResources) {
        for(QueuedContextStruct entry : this.queuedContexts) {
            if(entry.getContext() == context) {
                return;
            }
        }
        this.queuedContexts.add(new QueuedContextStruct(context, requiredResources));
        save();
    }

    public boolean unqueueContext(StepContext context) {
        for(Iterator<QueuedContextStruct> iter = this.queuedContexts.listIterator(); iter.hasNext();) {
            if(iter.next().getContext() == context) {
                iter.remove();
                save();
                return true;
            }
        }
        return false;
    }

    public static <T> Set<T> selectAmongPossibilities(List<Tuple2<Set<T>, Integer>> request) {
        if(request.size() <= 0) {
            return new HashSet<>();
        }
        Tuple2<Set<T>, Integer> t = request.get(0);
        Set<T> possibilities = t.getFirst();
        int n = t.getSecond();
        if(n <= 0) {
            List<Tuple2<Set<T>, Integer>> subrequest = new ArrayList<>(request);
            subrequest.remove(t);
            Set<T> res = selectAmongPossibilities(subrequest);
            if(res != null) {
                return res;
            }
        } else if(n <= possibilities.size()) {
            //Select each element, remove it from all other lists, then perform recursive call
            List<Tuple2<Set<T>, Integer>> subrequest = new ArrayList<>(request.size());
            for(T v : possibilities) {
                subrequest.clear();
                for(Tuple2<Set<T>, Integer> tt : request) {
                    Set<T> subpossibilities = new HashSet<>(tt.getFirst());
                    subpossibilities.remove(v);
                    int subn = ((tt == t) ? n - 1 : tt.getSecond());
                    subrequest.add(new Tuple2<>(subpossibilities, subn));
                }
                Set<T> res = selectAmongPossibilities(subrequest);
                if(res != null) {
                    res.add(v);
                    return res;
                }
            }
        }
        return null;
    }

    public static LockableResourcesManager get() {
        Jenkins jenkins = Jenkins.getInstance();
        if(jenkins != null) {
            return (LockableResourcesManager) jenkins.getDescriptorOrDie(LockableResourcesManager.class);
        }
        throw new IllegalStateException("Jenkins instance has not been started or was already shut down.");
    }
}
