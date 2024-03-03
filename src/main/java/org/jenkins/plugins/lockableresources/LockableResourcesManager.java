/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.BulkChange;
import hudson.Extension;
import hudson.Util;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Run;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkins.plugins.lockableresources.queue.QueuedContextStruct;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

@Extension
public class LockableResourcesManager extends GlobalConfiguration {

    /** Object to synchronized operations over LRM */
    public static final transient Object syncResources = new Object();

    private List<LockableResource> resources;
    private transient Cache<Long, List<LockableResource>> cachedCandidates =
            CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();
    private static final Logger LOGGER = Logger.getLogger(LockableResourcesManager.class.getName());

    /**
     * Only used when this lockable resource is tried to be locked by {@link LockStep}, otherwise
     * (freestyle builds) regular Jenkins queue is used.
     */
    private List<QueuedContextStruct> queuedContexts = new ArrayList<>();

    // cache to enable / disable saving lockable-resources state
    private int enableSave = -1;

    private static final int enabledBlockedCount =
            SystemProperties.getInteger(Constants.SYSTEM_PROPERTY_PRINT_BLOCKED_RESOURCE, 2);
    private static final int enabledCausesCount =
            SystemProperties.getInteger(Constants.SYSTEM_PROPERTY_PRINT_QUEUE_INFO, 2);

    // ---------------------------------------------------------------------------
    /** C-tor */
    @SuppressFBWarnings(
            value = "MC_OVERRIDABLE_METHOD_CALL_IN_CONSTRUCTOR",
            justification = "Common Jenkins pattern to call method that can be overridden")
    public LockableResourcesManager() {
        resources = new ArrayList<>();
        load();
    }

    // ---------------------------------------------------------------------------
    /** Get all resources Includes declared, ephemeral and node resources */
    public List<LockableResource> getResources() {
        return this.resources;
    }

    // ---------------------------------------------------------------------------
    /**
     * Get all resources - read only The same as getResources() but unmodifiable list. The
     * getResources() is unsafe to use because of possible concurrent modification exception.
     */
    @Restricted(NoExternalUse.class)
    public List<LockableResource> getReadOnlyResources() {
        synchronized (this.syncResources) {
            return new ArrayList<>(Collections.unmodifiableCollection(this.resources));
        }
    }

    // ---------------------------------------------------------------------------
    /** Get declared resources, means only defined in config file (xml or JCaC yaml). */
    public List<LockableResource> getDeclaredResources() {
        ArrayList<LockableResource> declaredResources = new ArrayList<>();
        for (LockableResource r : this.getReadOnlyResources()) {
            if (!r.isEphemeral() && !r.isNodeResource()) {
                declaredResources.add(r);
            }
        }
        return declaredResources;
    }

    // ---------------------------------------------------------------------------
    /** Set all declared resources (do not includes ephemeral and node resources). */
    @DataBoundSetter
    public void setDeclaredResources(List<LockableResource> declaredResources) {
        synchronized (this.syncResources) {
            Map<String, LockableResource> lockedResources = new HashMap<>();
            for (LockableResource r : this.resources) {
                if (!r.isLocked()) continue;
                lockedResources.put(r.getName(), r);
            }

            // Removed from configuration locks became ephemeral.
            ArrayList<LockableResource> mergedResources = new ArrayList<>();
            Set<String> addedLocks = new HashSet<>();
            for (LockableResource r : declaredResources) {
                if (!addedLocks.add(r.getName())) {
                    continue;
                }
                LockableResource locked = lockedResources.remove(r.getName());
                if (locked != null) {
                    // Merge already locked lock.
                    locked.setDescription(r.getDescription());
                    locked.setLabels(r.getLabels());
                    locked.setEphemeral(false);
                    locked.setNote(r.getNote());
                    mergedResources.add(locked);
                    continue;
                }
                mergedResources.add(r);
            }

            for (LockableResource r : lockedResources.values()) {
                // Removed locks became ephemeral.
                r.setDescription("");
                r.setLabels("");
                r.setNote("");
                r.setEphemeral(true);
                mergedResources.add(r);
            }

            this.resources = mergedResources;
        }
    }

    // ---------------------------------------------------------------------------
    /** Get all resources used by project. */
    @Restricted(NoExternalUse.class)
    public List<LockableResource> getResourcesFromProject(String fullName) {
        List<LockableResource> matching = new ArrayList<>();
        for (LockableResource r : this.getReadOnlyResources()) {
            String rName = r.getQueueItemProject();
            if (rName != null && rName.equals(fullName)) {
                matching.add(r);
            }
        }
        return matching;
    }

    // ---------------------------------------------------------------------------
    /** Get all resources used by build. */
    @Restricted(NoExternalUse.class)
    public List<LockableResource> getResourcesFromBuild(Run<?, ?> build) {
        List<LockableResource> matching = new ArrayList<>();
        for (LockableResource r : this.getReadOnlyResources()) {
            Run<?, ?> rBuild = r.getBuild();
            if (rBuild != null && rBuild == build) {
                matching.add(r);
            }
        }
        return matching;
    }

    // ---------------------------------------------------------------------------
    /**
     * Check if the label is valid. Valid in this context means, if is configured on someone resource.
     */
    @Restricted(NoExternalUse.class)
    public Boolean isValidLabel(@Nullable String label) {
        if (label == null || label.isEmpty()) {
            return false;
        }

        for (LockableResource r : this.getReadOnlyResources()) {
            if (r != null && r.isValidLabel(label)) {
                return true;
            }
        }

        return false;
    }

    // ---------------------------------------------------------------------------
    /** Returns all configured labels. */
    @NonNull
    @Restricted(NoExternalUse.class)
    public Set<String> getAllLabels() {
        Set<String> labels = new HashSet<>();
        for (LockableResource r : this.getReadOnlyResources()) {
            if (r == null) {
                continue;
            }
            List<String> toAdd = r.getLabelsAsList();
            if (toAdd.isEmpty()) {
                continue;
            }
            labels.addAll(toAdd);
        }
        return labels;
    }

    // ---------------------------------------------------------------------------
    /** Get amount of free resources contained given *label*
     *   This method is deprecated (no where used) and is not tested.
     */
    @NonNull
    @Restricted(NoExternalUse.class)
    @Deprecated
    @ExcludeFromJacocoGeneratedReport
    public int getFreeResourceAmount(String label) {
        int free = 0;
        label = Util.fixEmpty(label);

        if (label == null) {
            return free;
        }

        for (LockableResource r : this.getResourcesWithLabel(label)) {
            if (r == null) {
                continue;
            }
            if (r.isFree()) {
                free++;
            }
        }
        return free;
    }

    // ---------------------------------------------------------------------------
    /**
     * @deprecated Use getResourcesWithLabel(String label)
     * Note: The param *params* is not used (has no effect)
     */
    @Deprecated
    @Restricted(NoExternalUse.class)
    @ExcludeFromJacocoGeneratedReport
    public List<LockableResource> getResourcesWithLabel(String label, Map<String, Object> params) {
        return getResourcesWithLabel(label);
    }

    // ---------------------------------------------------------------------------
    /**
     * Returns resources matching by given *label*.
     */
    @NonNull
    @Restricted(NoExternalUse.class)
    public List<LockableResource> getResourcesWithLabel(final String label) {
        synchronized (this.syncResources) {
            return _getResourcesWithLabel(label, this.getResources());
        }
    }

    // ---------------------------------------------------------------------------
    @NonNull
    private static List<LockableResource> _getResourcesWithLabel(String label, final List<LockableResource> resources) {
        List<LockableResource> found = new ArrayList<>();
        label = Util.fixEmpty(label);

        if (label == null) {
            return found;
        }

        for (LockableResource r : resources) {
            if (r != null && r.isValidLabel(label)) found.add(r);
        }
        return found;
    }

    // ---------------------------------------------------------------------------
    /**
     * Returns a list of resources matching by given *script*.
     *
     * @param script Script
     * @param params Additional parameters
     * @return List of the matching resources
     * @throws ExecutionException Script execution failed for one of the resources. It is considered
     *     as a fatal failure since the requirement list may be incomplete
     * @since 2.0
     */
    @NonNull
    @Restricted(NoExternalUse.class)
    public List<LockableResource> getResourcesMatchingScript(
            @NonNull SecureGroovyScript script, @CheckForNull Map<String, Object> params) throws ExecutionException {
        List<LockableResource> found = new ArrayList<>();
        synchronized (this.syncResources) {
            for (LockableResource r : this.resources) {
                if (r.scriptMatches(script, params)) found.add(r);
            }
        }
        return found;
    }

    // ---------------------------------------------------------------------------
    /** Returns resource matched by name. Returns null in case, the resource does not exists. */
    @CheckForNull
    @Restricted(NoExternalUse.class)
    public LockableResource fromName(@CheckForNull String resourceName) {
        resourceName = Util.fixEmpty(resourceName);

        if (resourceName != null) {

            for (LockableResource r : this.getReadOnlyResources()) {
                if (resourceName.equals(r.getName())) return r;
            }
        } else {
            LOGGER.warning("Internal failure, fromName is empty or null:" + getStack());
        }
        return null;
    }

    // ---------------------------------------------------------------------------
    @Restricted(NoExternalUse.class)
    public List<LockableResource> fromNames(@Nullable final List<String> names) {
        if (names == null) {
            return null;
        }
        return fromNames(names, false);
    }

    // ---------------------------------------------------------------------------
    @Restricted(NoExternalUse.class)
    public List<LockableResource> fromNames(final List<String> names, final boolean createResource) {
        List<LockableResource> list = new ArrayList<>();
        for (String name : names) {
            // be sure it exists
            if (createResource) this.createResource(name);
            LockableResource r = this.fromName(name);
            if (r != null) // this is probably bug, but nobody know
            list.add(r);
        }
        return list;
    }

    // ---------------------------------------------------------------------------
    private String getStack() {
        StringBuilder buf = new StringBuilder();
        for (StackTraceElement st : Thread.currentThread().getStackTrace()) {
            buf.append("\n").append(st);
        }
        return buf.toString();
    }

    // ---------------------------------------------------------------------------
    /** Checks if given resource exist. */
    @NonNull
    @Restricted(NoExternalUse.class)
    public boolean resourceExist(@CheckForNull String resourceName) {
        return this.fromName(resourceName) != null;
    }

    // ---------------------------------------------------------------------------
    public boolean queue(List<LockableResource> resources, long queueItemId, String queueProjectName) {
        for (LockableResource r : resources) {
            if (r.isReserved() || r.isQueued(queueItemId) || r.isLocked()) {
                return false;
            }
        }
        for (LockableResource r : resources) {
            r.setQueued(queueItemId, queueProjectName);
        }
        return true;
    }

    // ---------------------------------------------------------------------------
    /**
     * @deprecated Use {@link
     *     #tryQueue(org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct, long,
     *     java.lang.String, int, java.util.Map, java.util.logging.Logger)}
     */
    @Deprecated
    @CheckForNull
    @ExcludeFromJacocoGeneratedReport
    @Restricted(NoExternalUse.class)
    public List<LockableResource> queue(
            LockableResourcesStruct requiredResources,
            long queueItemId,
            String queueItemProject,
            int number, // 0 means all
            Map<String, Object> params,
            Logger log) {
        try {
            return tryQueue(requiredResources, queueItemId, queueItemProject, number, params, log);
        } catch (ExecutionException ex) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                String itemName = queueItemProject + " (id=" + queueItemId + ")";
                LOGGER.log(
                        Level.WARNING, "Failed to queue item " + itemName, ex.getCause() != null ? ex.getCause() : ex);
            }
            return null;
        }
    }

    // ---------------------------------------------------------------------------
    /**
     * If the lockable resource availability was evaluated before and cached to avoid frequent
     * re-evaluations under queued pressure when there are no resources to give, we should state that
     * a resource is again instantly available for re-evaluation when we know it was busy and right
     * now is being freed. Note that a resource may be (both or separately) locked by a build and/or
     * reserved by a user (or stolen from build to user) so we only un-cache it here if it becomes
     * completely available. Called as a helper from methods that unlock/unreserve/reset (or
     * indirectly - recycle) stuff.
     *
     * <p>NOTE for people using LR or LRM methods directly to add some abilities in their pipelines
     * that are not provided by plugin: the `cachedCandidates` is an LRM concept, so if you tell a
     * resource (LR instance) directly to unlock/unreserve, it has no idea to clean itself from this
     * cache, and may be considered busy in queuing for some time afterwards.
     */
    public boolean uncacheIfFreeing(LockableResource candidate, boolean unlocking, boolean unreserving) {
        if (candidate.isLocked() && !unlocking) return false;

        // "stolen" state helps track that a resource is currently not
        // reserved for the same entity as it was originally given to;
        // this flag is cleared during un-reservation.
        if ((candidate.isReserved() || candidate.isStolen()) && !unreserving) return false;

        if (cachedCandidates.size() == 0) return true;

        // Per https://guava.dev/releases/19.0/api/docs/com/google/common/cache/Cache.html
        // "Modifications made to the map directly affect the cache."
        // so it is both a way for us to iterate the cache and to edit
        // the lists it stores per queue.
        Map<Long, List<LockableResource>> cachedCandidatesMap = cachedCandidates.asMap();
        for (Map.Entry<Long, List<LockableResource>> entry : cachedCandidatesMap.entrySet()) {
            Long queueItemId = entry.getKey();
            List<LockableResource> candidates = entry.getValue();
            if (candidates != null && (candidates.size() == 0 || candidates.contains(candidate))) {
                cachedCandidates.invalidate(queueItemId);
            }
        }

        return true;
    }

    // ---------------------------------------------------------------------------
    /**
     * Try to acquire the resources required by the task.
     *
     * @param number Number of resources to acquire. {@code 0} means all
     * @return List of the locked resources if the task has been accepted. {@code null} if the item is
     *     still waiting for the resources
     * @throws ExecutionException Cannot queue the resource due to the execution failure. Carries info
     *     in the cause
     * @since 2.0
     */
    @CheckForNull
    @Restricted(NoExternalUse.class)
    public List<LockableResource> tryQueue(
            LockableResourcesStruct requiredResources,
            long queueItemId,
            String queueItemProject,
            int number,
            Map<String, Object> params,
            Logger log)
            throws ExecutionException {
        List<LockableResource> selected = new ArrayList<>();
        synchronized (this.syncResources) {
            if (!checkCurrentResourcesStatus(selected, queueItemProject, queueItemId, log)) {
                // The project has another buildable item waiting -> bail out
                log.log(
                        Level.FINEST,
                        "{0} has another build waiting resources." + " Waiting for it to proceed first.",
                        new Object[] {queueItemProject});
                return null;
            }

            final SecureGroovyScript systemGroovyScript = requiredResources.getResourceMatchScript();
            boolean candidatesByScript = (systemGroovyScript != null);
            List<LockableResource> candidates = requiredResources.required; // default candidates

            if (candidatesByScript || (requiredResources.label != null && !requiredResources.label.isEmpty())) {

                candidates = cachedCandidates.getIfPresent(queueItemId);
                if (candidates != null) {
                    candidates.retainAll(this.resources);
                } else {
                    candidates = (systemGroovyScript == null)
                            ? getResourcesWithLabel(requiredResources.label)
                            : getResourcesMatchingScript(systemGroovyScript, params);
                    cachedCandidates.put(queueItemId, candidates);
                }
            }

            for (LockableResource rs : candidates) {
                if (number != 0 && (selected.size() >= number)) break;
                if (!rs.isReserved() && !rs.isLocked() && !rs.isQueued()) selected.add(rs);
            }

            // if did not get wanted amount or did not get all
            final int required_amount;
            if (candidatesByScript && candidates.isEmpty()) {
                /*
                 * If the groovy script does not return any candidates, it means nothing is needed, even if a
                 * higher amount is specified. A valid use case is a Matrix job, when not all configurations
                 * need resources.
                 */
                required_amount = 0;
            } else {
                required_amount = number == 0 ? candidates.size() : number;
            }

            if (selected.size() != required_amount) {
                log.log(
                        Level.FINEST,
                        "{0} found {1} resource(s) to queue." + "Waiting for correct amount: {2}.",
                        new Object[] {queueItemProject, selected.size(), required_amount});
                // just to be sure, clean up
                for (LockableResource x : this.resources) {
                    if (x.getQueueItemProject() != null
                            && x.getQueueItemProject().equals(queueItemProject)) x.unqueue();
                }
                return null;
            }

            for (LockableResource rsc : selected) {
                rsc.setQueued(queueItemId, queueItemProject);
            }
        }
        return selected;
    }

    // ---------------------------------------------------------------------------
    // Adds already selected (in previous queue round) resources to 'selected'
    // Return false if another item queued for this project -> bail out
    private boolean checkCurrentResourcesStatus(
            List<LockableResource> selected, String project, long taskId, Logger log) {
        for (LockableResource r : this.resources) {
            // This project might already have something in queue
            String rProject = r.getQueueItemProject();
            if (rProject != null && rProject.equals(project)) {
                if (r.isQueuedByTask(taskId)) {
                    // this item has queued the resource earlier
                    selected.add(r);
                } else {
                    // The project has another buildable item waiting -> bail out
                    log.log(
                            Level.FINEST,
                            "{0} has another build that already queued resource {1}. Continue queueing.",
                            new Object[] {project, r});
                    return false;
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------
    @Deprecated
    public boolean lock(List<LockableResource> resources, Run<?, ?> build, @Nullable StepContext context) {
        return this.lock(resources, build);
    }

    // ---------------------------------------------------------------------------
    @Deprecated
    public boolean lock(
            List<LockableResource> resources,
            Run<?, ?> build,
            @Nullable StepContext context,
            @Nullable String logmessage,
            final String variable,
            boolean inversePrecedence) {
        return this.lock(resources, build);
    }

    // ---------------------------------------------------------------------------
    /** Try to lock the resource and return true if locked. */
    public boolean lock(List<LockableResource> resources, Run<?, ?> build) {

        LOGGER.fine("lock it: " + resources + " for build " + build);

        if (build == null) {
            LOGGER.warning("lock() will fails, because the build does not exits. " + resources);
            return false; // not locked
        }

        String cause = getCauses(resources);
        if (!cause.isEmpty()) {
            LOGGER.warning("lock() for build " + build + " will fails, because " + cause);
            return false; // not locked
        }

        for (LockableResource r : resources) {
            r.unqueue();
            r.setBuild(build);
        }

        save();

        return true;
    }

    // ---------------------------------------------------------------------------
    private void freeResources(List<LockableResource> unlockResources, @Nullable Run<?, ?> build) {

        LOGGER.fine("free it: " + unlockResources);

        // make sure there is a list of resource names to unlock
        if (unlockResources == null || unlockResources.isEmpty()) {
            return;
        }

        List<LockableResource> toBeRemoved = new ArrayList<>();
        for (LockableResource resource : unlockResources) {
            // No more contexts, unlock resource
            if (build != null && build != resource.getBuild()) {
                continue; // this happens, when you push the unlock button in LRM page
            }
            resource.unqueue();
            resource.setBuild(null);
            uncacheIfFreeing(resource, true, false);

            if (resource.isEphemeral()) {
                LOGGER.info("Remove ephemeral resource: " + resource);
                toBeRemoved.add(resource);
            }
        }
        // remove all ephemeral resources
        removeResources(toBeRemoved);
    }

    // ---------------------------------------------------------------------------
    public void unlock(List<LockableResource> resourcesToUnLock, @Nullable Run<?, ?> build) {
        List<String> resourceNamesToUnLock = LockableResourcesManager.getResourcesNames(resourcesToUnLock);
        this.unlockNames(resourceNamesToUnLock, build);
    }

    // ---------------------------------------------------------------------------
    @Deprecated
    @ExcludeFromJacocoGeneratedReport
    public void unlock(
            @Nullable List<LockableResource> resourcesToUnLock, @Nullable Run<?, ?> build, boolean inversePrecedence) {
        unlock(resourcesToUnLock, build);
    }

    @Deprecated
    @ExcludeFromJacocoGeneratedReport
    public void unlockNames(
            @Nullable List<String> resourceNamesToUnLock, @Nullable Run<?, ?> build, boolean inversePrecedence) {
        this.unlockNames(resourceNamesToUnLock, build);
    }
    // ---------------------------------------------------------------------------
    @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION", justification = "not sure which exceptions might be catch.")
    public void unlockNames(@Nullable List<String> resourceNamesToUnLock, @Nullable Run<?, ?> build) {

        // make sure there is a list of resource names to unlock
        if (resourceNamesToUnLock == null || resourceNamesToUnLock.isEmpty()) {
            return;
        }

        synchronized (this.syncResources) {
            this.freeResources(this.fromNames(resourceNamesToUnLock), build);

            while (proceedNextContext()) {
                // process as many contexts as possible
            }

            save();
        }
    }

    private boolean proceedNextContext() {
        QueuedContextStruct nextContext = this.getNextQueuedContext();
        LOGGER.finest("nextContext: " + nextContext);
        // no context is queued which can be started once these resources are free'd.
        if (nextContext == null) {
            LOGGER.fine("No context is queued which can be started once these resources are free'd.");
            return false;
        }
        LOGGER.finest("nextContext candidates: " + nextContext.candidates);
        List<LockableResource> requiredResourceForNextContext =
                this.fromNames(nextContext.candidates, /*create un-existent resources */ true);
        LOGGER.finest("nextContext real candidates: " + requiredResourceForNextContext);
        // remove context from queue and process it

        Run<?, ?> build = nextContext.getBuild();
        if (build == null) {
            // this shall never happens
            // skip this context, as the build cannot be retrieved (maybe it was deleted while
            // running?)
            LOGGER.warning("Skip this context, as the build cannot be retrieved");
            return true;
        }
        boolean locked = this.lock(requiredResourceForNextContext, build);
        if (!locked) {
            // defensive line, shall never happens
            LOGGER.warning("Can not lock resources: " + requiredResourceForNextContext);
            // to eliminate possible endless loop
            return false;
        }

        // build env vars
        LinkedHashMap<String, List<LockableResourceProperty>> resourcesToLock = new LinkedHashMap<>();
        for (LockableResource requiredResource : requiredResourceForNextContext) {
            resourcesToLock.put(requiredResource.getName(), requiredResource.getProperties());
        }

        this.unqueueContext(nextContext.getContext());

        // continue with next context
        LockStepExecution.proceed(
                resourcesToLock,
                nextContext.getContext(),
                nextContext.getResourceDescription(),
                nextContext.getVariableName());
        return true;
    }

    // ---------------------------------------------------------------------------
    /** Returns names (IDs) of given *resources*. */
    @Restricted(NoExternalUse.class)
    public static List<String> getResourcesNames(final List<LockableResource> resources) {
        List<String> resourceNames = new ArrayList<>();
        if (resources != null) {
            for (LockableResource resource : resources) {
                resourceNames.add(resource.getName());
            }
        }
        return resourceNames;
    }

    // ---------------------------------------------------------------------------
    /** Returns names (IDs) off all existing resources (inclusive ephemeral) */
    @Restricted(NoExternalUse.class)
    public List<String> getAllResourcesNames() {
        synchronized (this.syncResources) {
            return getResourcesNames(this.resources);
        }
    }

    // ---------------------------------------------------------------------------
    /**
     * Returns the next queued context with all its requirements satisfied.
     *
     */
    @CheckForNull
    private QueuedContextStruct getNextQueuedContext() {

        LOGGER.fine("current queue size: " + this.queuedContexts.size());
        LOGGER.finest("current queue: " + this.queuedContexts);
        List<QueuedContextStruct> orphan = new ArrayList<>();
        QueuedContextStruct nextEntry = null;

        // the first one added lock is the oldest one, and this wins

        for (int idx = 0; idx < this.queuedContexts.size() && nextEntry == null; idx++) {
            QueuedContextStruct entry = this.queuedContexts.get(idx);
            // check queue list first
            if (!entry.isValid()) {
                LOGGER.fine("well be removed: " + idx + " " + entry);
                orphan.add(entry);
                continue;
            }
            LOGGER.finest("oldest win - index: " + idx + " " + entry);

            nextEntry = getNextQueuedContextEntry(entry);
        }

        if (!orphan.isEmpty()) {
            this.queuedContexts.removeAll(orphan);
        }

        return nextEntry;
    }

    // ---------------------------------------------------------------------------
    QueuedContextStruct getNextQueuedContextEntry(QueuedContextStruct entry) {
        List<LockableResource> candidates = this.getAvailableResources(entry.getResources());
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        entry.candidates = getResourcesNames(candidates);
        LOGGER.fine("take this: " + entry);
        return entry;
    }

    // ---------------------------------------------------------------------------
    /** Returns current queue */
    @Restricted(NoExternalUse.class) // used by jelly
    public List<QueuedContextStruct> getCurrentQueuedContext() {
        synchronized (this.syncResources) {
            return Collections.unmodifiableList(this.queuedContexts);
        }
    }

    // ---------------------------------------------------------------------------
    /** Creates the resource if it does not exist. */
    public boolean createResource(@CheckForNull String name) {
        name = Util.fixEmptyAndTrim(name);
        LockableResource resource = new LockableResource(name);
        resource.setEphemeral(true);

        return this.addResource(resource, /*doSave*/ true);
    }

    // ---------------------------------------------------------------------------
    public boolean createResourceWithLabel(@CheckForNull String name, @CheckForNull String label) {
        name = Util.fixEmptyAndTrim(name);
        label = Util.fixEmptyAndTrim(label);
        LockableResource resource = new LockableResource(name);
        resource.setLabels(label);

        return this.addResource(resource, /*doSave*/ true);
    }

    // ---------------------------------------------------------------------------
    public boolean createResourceWithLabelAndProperties(
            @CheckForNull String name, @CheckForNull String label, final Map<String, String> properties) {
        if (properties == null) {
            return false;
        }

        name = Util.fixEmptyAndTrim(name);
        label = Util.fixEmptyAndTrim(label);
        LockableResource resource = new LockableResource(name);
        resource.setLabels(label);
        resource.setProperties(properties.entrySet().stream()
                .map(e -> {
                    LockableResourceProperty p = new LockableResourceProperty();
                    p.setName(e.getKey());
                    p.setValue(e.getValue());
                    return p;
                })
                .collect(Collectors.toList()));

        return this.addResource(resource, /*doSave*/ true);
    }

    // ---------------------------------------------------------------------------
    @Restricted(NoExternalUse.class)
    public boolean addResource(@Nullable final LockableResource resource) {
        return this.addResource(resource, /*doSave*/ false);
    }
    // ---------------------------------------------------------------------------
    @Restricted(NoExternalUse.class)
    public boolean addResource(@Nullable final LockableResource resource, final boolean doSave) {

        if (resource == null || resource.getName() == null || resource.getName().isEmpty()) {
            LOGGER.warning("Internal failure: We will add wrong resource: '" + resource + "' " + getStack());
            return false;
        }
        synchronized (this.syncResources) {
            if (this.resourceExist(resource.getName())) {
                LOGGER.finest("We will add existing resource: " + resource + getStack());
                return false;
            }
            this.resources.add(resource);
            LOGGER.fine("Resource added : " + resource);
            if (doSave) {
                this.save();
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------
    /**
     * Reserves an available resource for the userName indefinitely (until that person, or some
     * explicit scripted action, decides to release the resource).
     */
    public boolean reserve(List<LockableResource> resources, String userName) {
        synchronized (this.syncResources) {
            for (LockableResource r : resources) {
                if (!r.isFree()) {
                    return false;
                }
            }
            for (LockableResource r : resources) {
                r.reserve(userName);
            }
            save();
        }
        return true;
    }

    // ---------------------------------------------------------------------------
    /**
     * Reserves a resource that may be or not be locked by some job (or reserved by some user)
     * already, giving it away to the userName indefinitely (until that person, or some explicit
     * scripted action, later decides to release the resource).
     */
    public boolean steal(List<LockableResource> resources, String userName) {
        synchronized (this.syncResources) {
            for (LockableResource r : resources) {
                r.setReservedBy(userName);
                r.setStolen();
            }
            unlock(resources, null, false);
            save();
        }
        return true;
    }

    // ---------------------------------------------------------------------------
    /**
     * Reserves a resource that may be or not be reserved by some person already, giving it away to
     * the userName indefinitely (until that person, or some explicit scripted action, decides to
     * release the resource).
     */
    public void reassign(List<LockableResource> resources, String userName) {
        synchronized (this.syncResources) {
            for (LockableResource r : resources) {
                if (!r.isFree()) {
                    r.unReserve();
                }
                r.setReservedBy(userName);
            }
            save();
        }
    }

    // ---------------------------------------------------------------------------
    private void unreserveResources(@NonNull List<LockableResource> resources) {
        for (LockableResource l : resources) {
            uncacheIfFreeing(l, false, true);
            l.unReserve();
        }
        save();
    }

    // ---------------------------------------------------------------------------
    @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION", justification = "not sure which exceptions might be catch.")
    public void unreserve(List<LockableResource> resources) {
        // make sure there is a list of resources to unreserve
        if (resources == null || resources.isEmpty()) {
            return;
        }

        synchronized (this.syncResources) {
            LOGGER.fine("unreserve " + resources);
            unreserveResources(resources);

            proceedNextContext();

            save();
        }
    }

    // ---------------------------------------------------------------------------
    @NonNull
    @Override
    public String getDisplayName() {
        return Messages.LockableResourcesManager_displayName();
    }

    // ---------------------------------------------------------------------------
    public void reset(List<LockableResource> resources) {
        synchronized (this.syncResources) {
            for (LockableResource r : resources) {
                uncacheIfFreeing(r, true, true);
                r.reset();
            }
            save();
        }
    }

    // ---------------------------------------------------------------------------
    /**
     * Make the lockable resource re-usable and notify the queue(s), if any WARNING: Do not use this
     * from inside the lock step closure which originally locked this resource, to avoid nasty
     * surprises! Namely, this *might* let a second consumer use the resource quickly, but when the
     * original closure ends and unlocks again that resource, a third consumer might then effectively
     * hijack it from the second one.
     */
    public void recycle(List<LockableResource> resources) {
        synchronized (this.syncResources) {
            // Not calling reset() because that also un-queues the resource
            // and we want to proclaim it is usable (if anyone is waiting)
            this.unlock(resources, null);
            this.unreserve(resources);
        }
    }

    @Restricted(NoExternalUse.class)
    public boolean changeQueueOrder(final String queueId, final int newIndex) {
        if (newIndex < 0) {
            LOGGER.warning("Given index is < 0. " + newIndex);
            return false;
        }
        synchronized (this.syncResources) {
            if (newIndex > this.queuedContexts.size() - 1) {
                LOGGER.warning("Given index is > queue. " + newIndex + " vs " + this.queuedContexts.size());
                return false;
            }

            QueuedContextStruct queueItem = null;
            int oldIndex = -1;
            for (int i = 0; i < this.queuedContexts.size(); i++) {
                QueuedContextStruct entry = this.queuedContexts.get(i);
                if (entry.getId().equals(queueId)) {
                    oldIndex = i;
                    break;
                }
            }

            if (oldIndex < 0) {
                LOGGER.warning("The queued entry does not exist, " + queueId);
                return false; // no more exists !?
            }

            Collections.swap(this.queuedContexts, oldIndex, newIndex);
        }
        return true;
    }

    // ---------------------------------------------------------------------------
    @Override
    public boolean configure(StaplerRequest req, JSONObject json) {
        synchronized (this.syncResources) {
            final List<LockableResource> oldDeclaredResources = new ArrayList<>(getDeclaredResources());

            try (BulkChange bc = new BulkChange(this)) {
                // reset resources to default which are not currently locked
                this.resources.removeIf(resource -> !resource.isLocked());
                req.bindJSON(this, json);
                bc.commit();
            } catch (IOException exception) {
                LOGGER.log(Level.WARNING, "Exception occurred while committing bulkchange operation.", exception);
                return false;
            }

            // Copy unconfigurable properties from old instances
            boolean updated = false;
            for (LockableResource oldDeclaredResource : oldDeclaredResources) {
                final LockableResource updatedResource = fromName(oldDeclaredResource.getName());
                if (updatedResource != null) {
                    updatedResource.copyUnconfigurableProperties(oldDeclaredResource);
                    updated = true;
                }
            }
            if (updated) {
                save();
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------
    public List<LockableResource> getAvailableResources(final List<LockableResourcesStruct> requiredResourcesList) {
        return this.getAvailableResources(requiredResourcesList, null, null);
    }

    // ---------------------------------------------------------------------------
    public List<LockableResource> getAvailableResources(final QueuedContextStruct entry) {
        return this.getAvailableResources(entry.getResources(), entry.getLogger(), null);
    }

    // ---------------------------------------------------------------------------
    public void removeResources(List<LockableResource> toBeRemoved) {
        synchronized (this.syncResources) {
            this.resources.removeAll(toBeRemoved);
        }
    }

    // ---------------------------------------------------------------------------
    /**
     * Checks if there are enough resources available to satisfy the requirements specified within
     * requiredResources and returns the necessary available resources. If not enough resources are
     * available, returns null.
     */
    public List<LockableResource> getAvailableResources(
            final List<LockableResourcesStruct> requiredResourcesList,
            final @Nullable PrintStream logger,
            final @Nullable ResourceSelectStrategy selectStrategy) {

        LOGGER.finest("getAvailableResources, " + requiredResourcesList);
        List<LockableResource> candidates = new ArrayList<>();
        for (LockableResourcesStruct requiredResources : requiredResourcesList) {
            List<LockableResource> available = new ArrayList<>();
            // filter by labels
            if (!StringUtils.isBlank(requiredResources.label)) {
                // get required amount first
                int requiredAmount = 0;
                if (requiredResources.requiredNumber != null) {
                    try {
                        requiredAmount = Integer.parseInt(requiredResources.requiredNumber);
                    } catch (NumberFormatException e) {
                        requiredAmount = 0;
                    }
                }

                available = this.getFreeResourcesWithLabel(
                        requiredResources.label, requiredAmount, selectStrategy, logger, candidates);
            } else if (requiredResources.required != null) {
                // resource by name requested

                // this is a little hack. The 'requiredResources.required' is a copy, and we need to find
                // all of them in LRM
                // fromNames() also re-create the resource (ephemeral things)
                available = fromNames(
                        getResourcesNames(requiredResources.required), /*create un-existent resources */ true);

                if (!this.areAllAvailable(available)) {
                    available = null;
                }
            } else {
                LOGGER.warning("getAvailableResources, Not implemented: " + requiredResources);
            }

            if (available == null || available.isEmpty()) {
                LOGGER.finest("No available resources found " + requiredResourcesList);
                return null;
            }

            final boolean isPreReserved = !Collections.disjoint(candidates, available);
            if (isPreReserved) {
                // FIXME I think this is failure
                // You use filter label1 and it lock resource1 and then in extra you will lock resource1
                // But when I allow this line, many tests will fails, and I am pretty sure it will throws
                // exceptions on end-user pipelines
                // So when we want to fix, it it might be braking-change
                // Therefore keep it here as warning for now
                printLogs("Extra filter tries to allocate pre-reserved resources.", logger, Level.WARNING);
                available.removeAll(candidates);
            }

            candidates.addAll(available);
        }

        return candidates;
    }

    // ---------------------------------------------------------------------------
    private boolean areAllAvailable(List<LockableResource> resources) {
        for (LockableResource resource : resources) {
            if (!resource.isFree()) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------
    public static void printLogs(final String msg, final Level level, Logger L, final @Nullable PrintStream logger) {
        L.log(level, msg);

        if (logger != null) {
            if (level == Level.WARNING || level == Level.SEVERE) logger.println(level.getLocalizedName() + ": " + msg);
            else logger.println(msg);
        }
    }

    // ---------------------------------------------------------------------------
    private static void printLogs(final String msg, final @Nullable PrintStream logger, final Level level) {
        printLogs(msg, level, LOGGER, logger);
    }

    // ---------------------------------------------------------------------------
    @CheckForNull
    @Restricted(NoExternalUse.class)
    private List<LockableResource> getFreeResourcesWithLabel(
            @NonNull String label,
            long amount,
            final @Nullable ResourceSelectStrategy selectStrategy,
            final @Nullable PrintStream logger,
            final List<LockableResource> alreadySelected) {
        List<LockableResource> found = new ArrayList<>();

        List<LockableResource> candidates = _getResourcesWithLabel(label, alreadySelected);
        candidates.addAll(this.getResourcesWithLabel(label));

        if (amount <= 0) {
            amount = candidates.size();
        }

        if (candidates.size() < amount) {
            printLogs(
                    "Found "
                            + found.size()
                            + " possible resource(s). Waiting for correct amount: "
                            + amount
                            + "."
                            + "This may remain stuck, until you create enough resources",
                    logger,
                    Level.WARNING);
            return null; // there are not enough resources
        }

        if (selectStrategy != null && selectStrategy.equals(ResourceSelectStrategy.RANDOM)) {
            Collections.shuffle(candidates);
        }

        for (LockableResource r : candidates) {
            // TODO: it shall be used isFree() here, but in that case we need to change the
            // logic in parametrized builds and that is much more effort as I want to spend here now
            if (!r.isReserved() && !r.isLocked()) {
                found.add(r);
            }

            if (amount > 0 && found.size() >= amount) {
                return found;
            }
        }

        String msg = "Found " + found.size() + " available resource(s). Waiting for correct amount: " + amount + ".";
        if (enabledBlockedCount != 0) {
            msg += "\nBlocking causes: " + getCauses(candidates);
        }
        printLogs(msg, logger, Level.FINE);

        return null;
    }

    // ---------------------------------------------------------------------------
    // for debug purpose
    private String getCauses(List<LockableResource> resources) {
        StringBuilder buf = new StringBuilder();
        int currentSize = 0;
        for (LockableResource resource : resources) {
            String cause = resource.getLockCauseDetail();
            if (cause == null) continue; // means it is free, not blocked

            currentSize++;
            if (enabledBlockedCount > 0 && currentSize == enabledBlockedCount) {
                buf.append("\n  ...");
                break;
            }
            buf.append("\n  ").append(cause);

            final String queueCause = getQueueCause(resource);
            if (!queueCause.isEmpty()) {
                buf.append(queueCause);
            }
        }
        return buf.toString();
    }

    // ---------------------------------------------------------------------------
    // for debug purpose
    private String getQueueCause(final LockableResource resource) {
        Map<Run<?, ?>, Integer> usage = new HashMap<>();

        for (QueuedContextStruct entry : this.queuedContexts) {

            Run<?, ?> build = entry.getBuild();
            if (build == null) {
                LOGGER.warning("Why we don`t have the build? " + entry);
                continue;
            }

            int count = 0;
            if (usage.containsKey(build)) {
                count = usage.get(build);
            }

            for (LockableResourcesStruct _struct : entry.getResources()) {
                if (_struct.isResourceRequired(resource)) {
                    LOGGER.fine("found " + resource + " " + count);
                    count++;
                    break;
                }
            }

            usage.put(build, count);
        }

        StringBuilder buf = new StringBuilder();
        int currentSize = 0;
        for (Map.Entry<Run<?, ?>, Integer> entry : usage.entrySet()) {
            Run<?, ?> build = entry.getKey();
            int count = entry.getValue();

            if (build != null && count > 0) {
                currentSize++;
                buf.append("\n    Queued ")
                        .append(count)
                        .append(" time(s) by build ")
                        .append(build.getFullDisplayName())
                        .append(" ")
                        .append(ModelHyperlinkNote.encodeTo(build));

                if (currentSize >= enabledCausesCount) {
                    buf.append("\n    ...");
                    break;
                }
            }
        }
        return buf.toString();
    }

    /*
     * Adds the given context and the required resources to the queue if
     * this context is not yet queued.
     */
    @Restricted(NoExternalUse.class)
    public void queueContext(
            StepContext context,
            List<LockableResourcesStruct> requiredResources,
            String resourceDescription,
            String variableName,
            boolean inversePrecedence,
            int priority) {
        synchronized (this.syncResources) {
            for (QueuedContextStruct entry : this.queuedContexts) {
                if (entry.getContext() == context) {
                    LOGGER.warning("queueContext, duplicated, " + requiredResources);
                    return;
                }
            }

            int queueIndex = 0;
            QueuedContextStruct newQueueItem =
                    new QueuedContextStruct(context, requiredResources, resourceDescription, variableName, priority);

            if (inversePrecedence) {
                queueIndex = 0;
            } else {
                queueIndex = this.queuedContexts.size() - 1;
                for (; queueIndex > 0; queueIndex--) {
                    QueuedContextStruct entry = this.queuedContexts.get(queueIndex);
                    // LOGGER.info("compare " + entry.toString());
                    if (entry.compare(newQueueItem) > 0) {
                        continue;
                    }
                    break;
                }
                queueIndex++;
            }

            this.queuedContexts.add(queueIndex, newQueueItem);
            printLogs(
                    requiredResources + " added into queue at position " + queueIndex,
                    newQueueItem.getLogger(),
                    Level.INFO);

            save();
        }
    }

    // ---------------------------------------------------------------------------
    public boolean unqueueContext(StepContext context) {
        synchronized (this.syncResources) {
            for (Iterator<QueuedContextStruct> iter = this.queuedContexts.listIterator(); iter.hasNext(); ) {
                if (iter.next().getContext() == context) {
                    iter.remove();
                    save();
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------
    public static LockableResourcesManager get() {
        return (LockableResourcesManager) Jenkins.get().getDescriptorOrDie(LockableResourcesManager.class);
    }

    // ---------------------------------------------------------------------------
    @Override
    public void save() {
        if (enableSave == -1) {
            // read system property and cache it.
            enableSave = SystemProperties.getBoolean(Constants.SYSTEM_PROPERTY_DISABLE_SAVE) ? 0 : 1;
        }

        if (enableSave == 0) return; // saving is disabled

        synchronized (this.syncResources) {
            if (BulkChange.contains(this)) return;

            try {
                getConfigFile().write(this);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to save " + getConfigFile(), e);
            }
        }
    }

    // ---------------------------------------------------------------------------
    /** For testing purpose. */
    @Restricted(NoExternalUse.class)
    public LockableResource getFirst() {
        return this.getResources().get(0);
    }
}
