/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
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
import hudson.init.Terminator;
import hudson.model.Descriptor;
import hudson.model.Run;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import net.sf.json.JSONObject;
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;
import org.jenkins.plugins.lockableresources.listeners.ResourceEvent;
import org.jenkins.plugins.lockableresources.listeners.ResourceEventListener;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkins.plugins.lockableresources.queue.QueuedContextStruct;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest2;

@Extension
public class LockableResourcesManager extends GlobalConfiguration {

    /** Object to synchronized operations over LRM */
    public static final Object syncResources = new Object();

    private List<LockableResource> resources;
    private final transient Cache<Long, List<LockableResource>> cachedCandidates =
            CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();
    private static final Logger LOGGER = Logger.getLogger(LockableResourcesManager.class.getName());

    private boolean allowEmptyOrNullValues;

    /**
     * Controls whether ephemeral resources can be created automatically.
     * When enabled (default), locking a non-existent resource creates it dynamically.
     * When disabled, locking a non-existent resource will block until it is manually created.
     */
    private boolean allowEphemeralResources = true;

    /** Groovy callback script executed when a resource changes state. */
    @CheckForNull
    private SecureGroovyScript onResourceEventScript;

    /** Whether the Groovy event callback runs asynchronously (default: true). */
    private boolean eventCallbackAsync = true;

    /** Timeout in seconds for the Groovy event callback (default: 30). */
    private int eventCallbackTimeoutSec = 30;

    /** Whether to silently log Groovy callback exceptions instead of propagating them (default: true). */
    private boolean eventCallbackIgnoreExceptions = true;

    /**
     * Only used when this lockable resource is tried to be locked by {@link LockStep}, otherwise
     * (freestyle builds) regular Jenkins queue is used.
     */
    private final List<QueuedContextStruct> queuedContexts = new ArrayList<>();

    // cache to enable / disable saving lockable-resources state
    private int enableSave = -1;

    private static final int enabledBlockedCount =
            SystemProperties.getInteger(Constants.SYSTEM_PROPERTY_PRINT_BLOCKED_RESOURCE, 2);
    private static final int enabledCausesCount =
            SystemProperties.getInteger(Constants.SYSTEM_PROPERTY_PRINT_QUEUE_INFO, 2);

    private static final boolean asyncSaveEnabled =
            SystemProperties.getBoolean(Constants.SYSTEM_PROPERTY_ASYNC_SAVE, true);
    private static final long saveCoalesceMs =
            SystemProperties.getLong(Constants.SYSTEM_PROPERTY_SAVE_COALESCE_MS, 1000L);

    private transient volatile AtomicBoolean savePending;
    private transient volatile ScheduledExecutorService saveExecutor;

    /** Single scheduled timeout task. Guarded by {@link #syncResources}. */
    private transient java.util.concurrent.ScheduledFuture<?> nextTimeoutTask;

    /** Deadline (epoch ms) the current {@link #nextTimeoutTask} targets. 0 = none. */
    private transient long nextTimeoutDeadline;

    @DataBoundSetter
    public void setAllowEmptyOrNullValues(boolean allowEmptyOrNullValues) {
        this.allowEmptyOrNullValues = allowEmptyOrNullValues;
    }

    public boolean isAllowEmptyOrNullValues() {
        return allowEmptyOrNullValues;
    }

    /**
     * Sets whether ephemeral resources can be created automatically.
     *
     * @param allowEphemeralResources true to allow automatic creation of ephemeral resources
     */
    @DataBoundSetter
    public void setAllowEphemeralResources(boolean allowEphemeralResources) {
        this.allowEphemeralResources = allowEphemeralResources;
    }

    /**
     * Returns whether ephemeral resources are allowed.
     *
     * @return true if ephemeral resources can be created automatically
     */
    public boolean isAllowEphemeralResources() {
        return allowEphemeralResources;
    }

    @CheckForNull
    public SecureGroovyScript getOnResourceEventScript() {
        return onResourceEventScript;
    }

    @DataBoundSetter
    public void setOnResourceEventScript(@CheckForNull SecureGroovyScript onResourceEventScript) {
        this.onResourceEventScript =
                onResourceEventScript != null ? onResourceEventScript.configuringWithKeyItem() : null;
    }

    public boolean isEventCallbackAsync() {
        return eventCallbackAsync;
    }

    @DataBoundSetter
    public void setEventCallbackAsync(boolean eventCallbackAsync) {
        this.eventCallbackAsync = eventCallbackAsync;
    }

    public int getEventCallbackTimeoutSec() {
        return eventCallbackTimeoutSec;
    }

    @DataBoundSetter
    public void setEventCallbackTimeoutSec(int eventCallbackTimeoutSec) {
        this.eventCallbackTimeoutSec = eventCallbackTimeoutSec;
    }

    public boolean isEventCallbackIgnoreExceptions() {
        return eventCallbackIgnoreExceptions;
    }

    @DataBoundSetter
    public void setEventCallbackIgnoreExceptions(boolean eventCallbackIgnoreExceptions) {
        this.eventCallbackIgnoreExceptions = eventCallbackIgnoreExceptions;
    }

    // ---------------------------------------------------------------------------
    /** C-tor */
    @SuppressFBWarnings(
            value = "MC_OVERRIDABLE_METHOD_CALL_IN_CONSTRUCTOR",
            justification = "Common Jenkins pattern to call method that can be overridden")
    public LockableResourcesManager() {
        resources = new ArrayList<>();
        load();
        // SecureGroovyScript requires configuring() before evaluate() can be called.
        // When deserialized from XML, the setter is not invoked, so configure here.
        if (onResourceEventScript != null) {
            onResourceEventScript = onResourceEventScript.configuringWithNonKeyItem();
        }
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
        synchronized (syncResources) {
            return new ArrayList<>(Collections.unmodifiableCollection(this.resources));
        }
    }

    // ---------------------------------------------------------------------------
    /** Get declared resources, means only defined in config file (xml or JCaC yaml). */
    @Restricted(NoExternalUse.class)
    public List<LockableResource> getDeclaredResources() {
        ArrayList<LockableResource> declaredResources = new ArrayList<>();
        for (LockableResource r : this.getResources()) {
            if (!r.isEphemeral() && !r.isNodeResource()) {
                declaredResources.add(r);
            }
        }
        return declaredResources;
    }

    // ---------------------------------------------------------------------------
    /** Set all declared resources (do not include ephemeral and node resources). */
    @DataBoundSetter
    public void setDeclaredResources(List<LockableResource> declaredResources) {
        synchronized (syncResources) {
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

            // Copy reservations and unconfigurable properties from old instances. Clear unconfigurable
            // properties for new resources: they should be empty anyway for new resources from UI
            // configuration. For CasC configuration, we ignore those fields, so set them to empty.
            for (LockableResource newResource : mergedResources) {
                final LockableResource oldDeclaredResource = fromName(newResource.getName());
                if (oldDeclaredResource != null) {
                    newResource.copyUnconfigurableProperties(oldDeclaredResource);
                } else {
                    newResource.resetUnconfigurableProperties();
                }
            }

            this.resources = mergedResources;
            save();
        }
    }

    // ---------------------------------------------------------------------------
    /** Get all resources used by project. */
    @Restricted(NoExternalUse.class)
    public List<LockableResource> getResourcesFromProject(String fullName) {
        List<LockableResource> matching = new ArrayList<>();
        for (LockableResource r : this.getResources()) {
            String rName = r.getQueueItemProject();
            if (rName != null && rName.equals(fullName)) {
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

        synchronized (syncResources) {
            for (LockableResource r : this.getResources()) {
                if (r != null && r.isValidLabel(label)) {
                    return true;
                }
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
        synchronized (syncResources) {
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
        synchronized (syncResources) {
            for (LockableResource r : this.resources) {
                if (r.scriptMatches(script, params)) found.add(r);
            }
        }
        return found;
    }

    // ---------------------------------------------------------------------------
    /** Returns resource matched by name. Returns null in case, the resource does not exist. */
    @CheckForNull
    @Restricted(NoExternalUse.class)
    public LockableResource fromName(@CheckForNull String resourceName) {
        resourceName = Util.fixEmpty(resourceName);

        if (resourceName != null) {

            synchronized (syncResources) {
                for (LockableResource r : this.getResources()) {
                    if (resourceName.equals(r.getName())) return r;
                }
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
    public Boolean resourceExist(@CheckForNull String resourceName) {
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
     * cache, and may be considered busy in queuing for some time afterward.
     */
    public boolean uncacheIfFreeing(LockableResource candidate, boolean unlocking, boolean unreserving) {
        if (candidate == null) return false;
        if (candidate.isLocked() && !unlocking) return false;

        // "stolen" state helps track that a resource is currently not
        // reserved for the same entity as it was originally given to;
        // this flag is cleared during un-reservation.
        if ((candidate.isReserved() || candidate.isStolen()) && !unreserving) return false;

        if (cachedCandidates.size() == 0) return true;

        // Take a snapshot of keys to avoid ConcurrentModificationException.
        // Only invalidate entries that actually contain the freed resource,
        // preserving cache for other queue items (important at scale with 1000+ items).
        Set<Long> keys = new HashSet<>(cachedCandidates.asMap().keySet());
        for (Long queueItemId : keys) {
            List<LockableResource> candidates = cachedCandidates.getIfPresent(queueItemId);
            if (candidates != null && (candidates.isEmpty() || candidates.contains(candidate))) {
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

        final SecureGroovyScript systemGroovyScript;
        try {
            systemGroovyScript = requiredResources.getResourceMatchScript();
        } catch (Descriptor.FormException x) {
            throw new ExecutionException(x);
        }
        boolean candidatesByScript = (systemGroovyScript != null);

        // Resolve candidates outside syncResources when possible — label matching
        // and Groovy script evaluation are heavyweight and should not extend the
        // critical section.
        // NOTE: We store unmodifiable lists in the cache for thread-safety. Multiple
        // threads may read from the cache concurrently, so cached lists must not be
        // modified. Create a mutable copy below when modifications are needed.
        List<LockableResource> candidates = null;
        if (candidatesByScript || (requiredResources.label != null && !requiredResources.label.isEmpty())) {
            candidates = cachedCandidates.getIfPresent(queueItemId);
            if (candidates == null) {
                candidates = (systemGroovyScript == null)
                        ? getResourcesWithLabel(requiredResources.label)
                        : getResourcesMatchingScript(systemGroovyScript, params);
                // Store as unmodifiable to prevent accidental modification of cached data
                cachedCandidates.put(queueItemId, Collections.unmodifiableList(candidates));
            }
        }

        List<LockableResource> selected = new ArrayList<>();
        synchronized (syncResources) {
            if (!checkCurrentResourcesStatus(selected, queueItemProject, queueItemId, log)) {
                // The project has another buildable item waiting -> bail out
                log.log(
                        Level.FINEST,
                        "{0} has another build waiting resources." + " Waiting for it to proceed first.",
                        new Object[] {queueItemProject});
                return null;
            }

            if (candidates != null) {
                // Mutable copy required - cached list is unmodifiable for thread-safety
                candidates = new ArrayList<>(candidates);
                candidates.retainAll(this.resources);
            } else {
                candidates = requiredResources.required;
            }

            for (LockableResource rs : candidates) {
                if (number != 0 && (selected.size() >= number)) break;
                if (!rs.isReserved() && !rs.isLocked() && !rs.isQueued()) selected.add(rs);
            }

            // if did not get wanted amount or did not get all
            final int required_amount = getRequiredAmount(number, candidatesByScript, candidates);

            if (selected.size() != required_amount) {
                log.log(
                        Level.FINEST,
                        "{0} found {1} resource(s) to queue. Waiting for correct amount: {2}.",
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
    /**
     * Returns the amount of resources required by the task.
     * If the groovy script does not return any candidates, it means nothing is needed, even if a
     * higher amount is specified. A valid use case is a Matrix job, when not all configurations need resources.
     */
    private static int getRequiredAmount(int number, boolean candidatesByScript, List<LockableResource> candidates) {
        final int required_amount;
        if (candidatesByScript && candidates.isEmpty()) {
            required_amount = 0;
        } else {
            required_amount = number == 0 ? candidates.size() : number;
        }
        return required_amount;
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
    public boolean lock(List<LockableResource> resourcesToLock, Run<?, ?> build) {
        return lock(resourcesToLock, build, (String) null);
    }

    // ---------------------------------------------------------------------------
    /**
     * Try to lock the resource and return true if locked.
     *
     * @param resourcesToLock The resources to lock.
     * @param build The build that is locking the resources.
     * @param reason The reason why the resources are being locked (displayed in UI).
     * @return true if locked successfully.
     */
    public boolean lock(List<LockableResource> resourcesToLock, Run<?, ?> build, @Nullable String reason) {

        LOGGER.fine("lock it: " + resourcesToLock + " for build " + build + " with reason: " + reason);

        if (build == null) {
            LOGGER.warning("lock() will fails, because the build does not exits. " + resourcesToLock);
            return false; // not locked
        }

        String cause = getCauses(resourcesToLock);
        if (!cause.isEmpty()) {
            LOGGER.warning("lock() for build " + build + " will fails, because " + cause);
            return false; // not locked
        }

        for (LockableResource r : resourcesToLock) {
            r.unqueue();
            r.setBuild(build);
            if (reason != null && !reason.isEmpty()) {
                r.setLockReason(reason);
            }
        }

        LockedResourcesBuildAction.findAndInitAction(build).addUsedResources(getResourcesNames(resourcesToLock));

        save();
        ResourceEventListener.fireEvent(ResourceEvent.LOCKED, resourcesToLock, build, null);

        return true;
    }

    // ---------------------------------------------------------------------------
    private void freeResources(List<LockableResource> unlockResources, Run<?, ?> build) {

        LOGGER.fine("free it: " + unlockResources);

        // make sure there is a list of resource names to unlock
        if (unlockResources == null || unlockResources.isEmpty() || build == null) {
            return;
        }

        List<LockableResource> toBeRemoved = new ArrayList<>();
        List<LockableResource> freed = new ArrayList<>();

        for (LockableResource resource : unlockResources) {
            // No more contexts, unlock resource

            // the resource has been currently unlocked (like by LRM page - button unlock, or by API)
            if (!build.equals(resource.getBuild())) continue;

            resource.unqueue();
            resource.setBuild(null);
            resource.setLockReason(null);
            uncacheIfFreeing(resource, true, false);
            freed.add(resource);

            if (resource.isEphemeral()) {
                LOGGER.fine("Remove ephemeral resource: " + resource);
                toBeRemoved.add(resource);
            }
        }

        LockedResourcesBuildAction.findAndInitAction(build).removeUsedResources(getResourcesNames(unlockResources));

        // remove all ephemeral resources
        removeResources(toBeRemoved);

        if (!freed.isEmpty()) {
            ResourceEventListener.fireEvent(ResourceEvent.UNLOCKED, freed, build, null);
        }
    }

    public void unlockBuild(@Nullable Run<?, ?> build) {

        if (build == null) {
            return;
        }

        List<String> resourcesInUse =
                LockedResourcesBuildAction.findAndInitAction(build).getCurrentUsedResourceNames();

        if (resourcesInUse.isEmpty()) {
            return;
        }
        unlockNames(resourcesInUse, build);
    }

    // ---------------------------------------------------------------------------
    public void unlockNames(@Nullable List<String> resourceNamesToUnLock, Run<?, ?> build) {

        // make sure there is a list of resource names to unlock
        if (resourceNamesToUnLock == null || resourceNamesToUnLock.isEmpty()) {
            return;
        }
        synchronized (syncResources) {
            unlockResources(this.fromNames(resourceNamesToUnLock), build);
        }
    }

    // ---------------------------------------------------------------------------
    public void unlockResources(List<LockableResource> resourcesToUnLock) {
        unlockResources(resourcesToUnLock, resourcesToUnLock.get(0).getBuild());
    }

    // ---------------------------------------------------------------------------
    public void unlockResources(List<LockableResource> resourcesToUnLock, Run<?, ?> build) {
        if (resourcesToUnLock == null || resourcesToUnLock.isEmpty()) {
            return;
        }
        synchronized (syncResources) {
            this.freeResources(resourcesToUnLock, build);

            while (proceedNextContext()) {
                // process as many contexts as possible
            }

            save();
        }
        scheduleQueueMaintenance();
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
            // this shall never happen
            // skip this context, as the build cannot be retrieved (maybe it was deleted while
            // running?)
            LOGGER.warning("Skip this context, as the build cannot be retrieved");
            return true;
        }
        boolean locked = this.lock(requiredResourceForNextContext, build, nextContext.getReason());
        if (!locked) {
            // defensive line, shall never happen
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
        LOGGER.fine("Continue with next context: " + nextContext);
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
        synchronized (syncResources) {
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
        List<QueuedContextStruct> toRemove = new ArrayList<>();
        QueuedContextStruct nextEntry = null;
        long earliestDeadline = Long.MAX_VALUE;

        // the first one added lock is the oldest one, and this wins

        for (int idx = 0; idx < this.queuedContexts.size() && nextEntry == null; idx++) {
            QueuedContextStruct entry = this.queuedContexts.get(idx);
            // check queue list first
            if (!entry.isValid()) {
                LOGGER.fine("well be removed: " + idx + " " + entry);
                toRemove.add(entry);
                continue;
            }

            // check if the entry has timed out waiting for resources
            if (entry.isTimedOut()) {
                LOGGER.info("Queue entry timed out waiting for resources: " + entry);
                toRemove.add(entry);
                PrintStream logger = entry.getLogger();
                String msg = "[" + entry.getResourceDescription()
                        + "] timed out waiting for resource allocation after "
                        + entry.getTimeoutForAllocateResource() + " "
                        + entry.getTimeoutUnit().toLowerCase(java.util.Locale.ENGLISH);
                printLogs(msg, logger, Level.WARNING);
                entry.getContext()
                        .onFailure(new org.jenkins.plugins.lockableresources.queue.LockWaitTimeoutException(msg));
                continue;
            }

            // track the earliest deadline among remaining entries for rescheduling
            long deadline = entry.getTimeoutDeadlineMillis();
            if (deadline > 0 && deadline < earliestDeadline) {
                earliestDeadline = deadline;
            }

            LOGGER.finest("oldest win - index: " + idx + " " + entry);

            nextEntry = getNextQueuedContextEntry(entry);
        }

        if (!toRemove.isEmpty()) {
            this.queuedContexts.removeAll(toRemove);
        }

        // reschedule for the next earliest deadline
        scheduleTimeoutAt(earliestDeadline);

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
        synchronized (syncResources) {
            return Collections.unmodifiableList(this.queuedContexts);
        }
    }

    // ---------------------------------------------------------------------------
    /**
     * Creates the resource if it does not exist and ephemeral resources are allowed.
     *
     * @param name the resource name
     * @return true if resource was created, false if it already exists or ephemeral resources are
     *     disabled
     */
    public boolean createResource(@CheckForNull String name) {
        if (!allowEphemeralResources) {
            LOGGER.fine("Ephemeral resources are disabled, not creating resource: " + name);
            return false;
        }
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
        synchronized (syncResources) {
            if (this.resourceExist(resource.getName())) {
                LOGGER.finest("We will add existing resource: " + resource + getStack());
                return false;
            }
            this.resources.add(resource);
            LOGGER.fine("Resource added : " + resource);

            // Invalidate cache and process waiting pipeline jobs while still holding the lock
            cachedCandidates.invalidateAll();
            while (proceedNextContext()) {
                // process as many contexts as possible
            }

            if (doSave) {
                this.save();
            }
        }
        // Notify Jenkins queue for freestyle jobs (must be outside synchronized block)
        scheduleQueueMaintenance();
        return true;
    }

    // ---------------------------------------------------------------------------
    /**
     * Reserves an available resource for the userName indefinitely (until that person, or some
     * explicit scripted action, decides to release the resource).
     */
    public boolean reserve(List<LockableResource> resources, String userName) {
        return reserve(resources, userName, null);
    }

    // ---------------------------------------------------------------------------
    /**
     * Reserves an available resource for the userName indefinitely (until that person, or some
     * explicit scripted action, decides to release the resource).
     *
     * @param resources list of resources to reserve
     * @param userName the user reserving the resources
     * @param reason the reason for reserving (optional)
     * @return true if all resources were successfully reserved, false if any was not free
     */
    public boolean reserve(List<LockableResource> resources, String userName, String reason) {
        LOGGER.info("reserve() called user='" + userName + "' resources=" + getResourcesNames(resources) + " reason='"
                + reason + "'");
        synchronized (syncResources) {
            for (LockableResource r : resources) {
                if (!r.isFree()) {
                    LOGGER.fine("reserve() failed because resource not free: " + r.getName());
                    return false;
                }
            }
            for (LockableResource r : resources) {
                r.reserve(userName, reason);
            }
            save();
        }
        ResourceEventListener.fireEvent(ResourceEvent.RESERVED, resources, null, userName);
        LOGGER.info("reserve() succeeded user='" + userName + "' resources=" + getResourcesNames(resources));
        return true;
    }

    // ---------------------------------------------------------------------------
    /**
     * Reserves a resource that may be or not be locked by some job (or reserved by some user)
     * already, giving it away to the userName indefinitely (until that person, or some explicit
     * scripted action, later decides to release the resource).
     */
    public boolean steal(List<LockableResource> resources, String userName) {
        return steal(resources, userName, null);
    }

    // ---------------------------------------------------------------------------
    /**
     * Reserves a resource that may be or not be locked by some job (or reserved by some user)
     * already, giving it away to the userName indefinitely (until that person, or some explicit
     * scripted action, later decides to release the resource).
     *
     * @param resources list of resources to steal
     * @param userName the user stealing the resources
     * @param reason the reason for stealing (optional)
     * @return true if stolen successfully
     */
    public boolean steal(List<LockableResource> resources, String userName, String reason) {
        synchronized (syncResources) {
            for (LockableResource r : resources) {
                r.setReservedBy(userName);
                r.setStolen();
            }
            unlockResources(resources);
            Date date = new Date();
            for (LockableResource r : resources) {
                r.setReservedTimestamp(date);
                r.setLockReason(reason);
            }
            save();
        }
        ResourceEventListener.fireEvent(ResourceEvent.STOLEN, resources, null, userName);
        return true;
    }

    // ---------------------------------------------------------------------------
    /**
     * Reserves a resource that may be or not be reserved by some person already, giving it away to
     * the userName indefinitely (until that person, or some explicit scripted action, decides to
     * release the resource).
     */
    public void reassign(List<LockableResource> resources, String userName) {
        synchronized (syncResources) {
            Date date = new Date();
            for (LockableResource r : resources) {
                if (!r.isFree()) {
                    r.unReserve();
                }
                r.setReservedBy(userName);
                r.setReservedTimestamp(date);
            }
            save();
        }
        ResourceEventListener.fireEvent(ResourceEvent.REASSIGNED, resources, null, userName);
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
    public void unreserve(List<LockableResource> resources) {
        // make sure there is a list of resources to unreserve
        if (resources == null || resources.isEmpty()) {
            return;
        }

        synchronized (syncResources) {
            LOGGER.fine("unreserve " + resources);
            unreserveResources(resources);

            proceedNextContext();

            save();
        }
        ResourceEventListener.fireEvent(ResourceEvent.UNRESERVED, resources, null, null);
        scheduleQueueMaintenance();
    }

    // ---------------------------------------------------------------------------
    @NonNull
    @Override
    public String getDisplayName() {
        return Messages.LockableResourcesManager_displayName();
    }

    // ---------------------------------------------------------------------------
    public void reset(List<LockableResource> resources) {
        synchronized (syncResources) {
            for (LockableResource r : resources) {
                uncacheIfFreeing(r, true, true);
                r.reset();
            }
            save();
        }
        ResourceEventListener.fireEvent(ResourceEvent.RESET, resources, null, null);
        scheduleQueueMaintenance();
    }

    // ---------------------------------------------------------------------------
    /**
     * Make the lockable resource reusable and notify the queue(s), if any WARNING: Do not use this
     * from inside the lock step closure which originally locked this resource, to avoid nasty
     * surprises! Namely, this *might* let a second consumer use the resource quickly, but when the
     * original closure ends and unlocks again that resource, a third consumer might then effectively
     * hijack it from the second one.
     */
    public void recycle(List<LockableResource> resources) {
        synchronized (syncResources) {
            // Not calling reset() because that also un-queues the resource
            // and we want to proclaim it is usable (if anyone is waiting)
            this.unlockResources(resources);
            this.unreserve(resources);
        }
        ResourceEventListener.fireEvent(ResourceEvent.RECYCLED, resources, null, null);
    }

    // ---------------------------------------------------------------------------
    /** Change the order (position) of the given item in the queue*/
    @Restricted(NoExternalUse.class) // used by jelly
    public void changeQueueOrder(final String queueId, final int newPosition) throws IOException {
        synchronized (syncResources) {
            if (newPosition < 0 || newPosition >= this.queuedContexts.size()) {
                throw new IOException(
                        Messages.error_queuePositionOutOfRange(newPosition + 1, this.queuedContexts.size()));
            }

            int oldIndex = -1;
            for (int i = 0; i < this.queuedContexts.size(); i++) {
                QueuedContextStruct entry = this.queuedContexts.get(i);
                if (entry.getId().equals(queueId)) {
                    oldIndex = i;
                    break;
                }
            }

            if (oldIndex < 0) {
                // no more exists !?
                throw new IOException(Messages.error_queueDoesNotExist(queueId));
            }

            Collections.swap(this.queuedContexts, oldIndex, newPosition);
        }
    }

    // ---------------------------------------------------------------------------
    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) {
        synchronized (syncResources) {
            try (BulkChange bc = new BulkChange(this)) {
                req.bindJSON(this, json);
                bc.commit();
            } catch (IOException exception) {
                LOGGER.log(Level.WARNING, "Exception occurred while committing bulkchange operation.", exception);
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------
    public List<LockableResource> getAvailableResources(final List<LockableResourcesStruct> requiredResourcesList) {
        return this.getAvailableResources(requiredResourcesList, null, null);
    }

    // ---------------------------------------------------------------------------
    /** Function removes all given resources */
    public void removeResources(List<LockableResource> toBeRemoved) {
        synchronized (syncResources) {
            this.resources.removeAll(toBeRemoved);
        }
        scheduleQueueMaintenance();
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
            if (requiredResources.label != null && !requiredResources.label.isBlank()) {
                // get required amount first
                int requiredAmount = 0;
                if (requiredResources.requiredNumber != null) {
                    try {
                        requiredAmount = Integer.parseInt(requiredResources.requiredNumber);
                    } catch (NumberFormatException ignored) {
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
                            + candidates.size()
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
        queueContext(
                context,
                requiredResources,
                resourceDescription,
                variableName,
                inversePrecedence,
                priority,
                null,
                0,
                "MINUTES");
    }

    // ---------------------------------------------------------------------------
    /*
     * Adds the given context and the required resources to the queue if
     * this context is not yet queued, with reason and timeout for resource allocation.
     */
    @Restricted(NoExternalUse.class)
    public void queueContext(
            StepContext context,
            List<LockableResourcesStruct> requiredResources,
            String resourceDescription,
            String variableName,
            boolean inversePrecedence,
            int priority,
            String reason,
            long timeoutForAllocateResource,
            String timeoutUnit) {
        synchronized (syncResources) {
            for (QueuedContextStruct entry : this.queuedContexts) {
                if (entry.getContext() == context) {
                    LOGGER.warning("queueContext, duplicated, " + requiredResources);
                    return;
                }
            }

            int queueIndex = 0;
            QueuedContextStruct newQueueItem = new QueuedContextStruct(
                    context,
                    requiredResources,
                    resourceDescription,
                    variableName,
                    priority,
                    reason,
                    timeoutForAllocateResource,
                    timeoutUnit);

            if (!inversePrecedence || priority != 0) {
                queueIndex = this.queuedContexts.size() - 1;
                for (; queueIndex >= 0; queueIndex--) {
                    QueuedContextStruct entry = this.queuedContexts.get(queueIndex);
                    final int rc = entry.compare(newQueueItem);
                    if (rc > 0) {
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
                    Level.FINE);

            save();

            // If this entry has a timeout and its deadline is earlier than the
            // currently scheduled one, (re)schedule so it fires on time.
            long deadline = newQueueItem.getTimeoutDeadlineMillis();
            if (deadline > 0 && (nextTimeoutDeadline == 0 || deadline < nextTimeoutDeadline)) {
                scheduleTimeoutAt(deadline);
            }
        }
    }

    // ---------------------------------------------------------------------------
    public boolean unqueueContext(StepContext context) {
        synchronized (syncResources) {
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
    /**
     * Trigger an immediate Queue re-evaluation so items waiting for lockable
     * resources are dispatched as soon as resources become available, instead of
     * waiting for the next 5-second timer tick.
     * <p>
     * Must be called <b>outside</b> {@code synchronized(syncResources)} to avoid
     * holding the plugin lock while Jenkins acquires the Queue lock.
     */
    public static void scheduleQueueMaintenance() {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j != null) {
            j.getQueue().scheduleMaintenance();
        }
    }

    // ---------------------------------------------------------------------------
    /**
     * Refresh the queue to allow waiting jobs to re-evaluate available resources.
     * <p>
     * This method should be called after modifying labels on existing resources,
     * as label changes do not automatically trigger queue re-evaluation.
     * <p>
     * It performs the following actions:
     * <ol>
     *   <li>Invalidates the cached resource candidates</li>
     *   <li>Processes waiting pipeline job contexts</li>
     *   <li>Triggers Jenkins queue maintenance for freestyle jobs</li>
     * </ol>
     */
    public void refreshQueue() {
        // Invalidate cached candidates so waiting jobs re-evaluate with current labels
        cachedCandidates.invalidateAll();

        // Process waiting pipeline jobs (also handles timeouts)
        synchronized (syncResources) {
            while (proceedNextContext()) {
                // process as many contexts as possible
            }
        }

        // Notify Jenkins queue for freestyle jobs
        scheduleQueueMaintenance();
    }

    // ---------------------------------------------------------------------------
    /**
     * Checks for timed-out entries in the pipeline lock queue and fails them.
     * Called by {@link org.jenkins.plugins.lockableresources.queue.LockWaitTimeoutPeriodicWork}
     * as a safety net.
     */
    @Restricted(NoExternalUse.class)
    public void checkTimeouts() {
        synchronized (syncResources) {
            // proceedNextContext → getNextQueuedContext handles timeouts + rescheduling
            while (proceedNextContext()) {
                // process as many contexts as possible
            }
        }
    }

    // ---------------------------------------------------------------------------
    /**
     * Schedules (or reschedules) the single timeout task to fire at the given
     * deadline. If {@code deadline} is {@link Long#MAX_VALUE} the current task
     * is cancelled and nothing new is scheduled.
     * Must be called while holding {@link #syncResources}.
     */
    private void scheduleTimeoutAt(long deadline) {
        // Cancel the current task — we will either replace it or clear it
        if (nextTimeoutTask != null) {
            nextTimeoutTask.cancel(false);
            nextTimeoutTask = null;
            nextTimeoutDeadline = 0;
        }

        if (deadline == Long.MAX_VALUE || deadline <= 0) {
            return;
        }

        nextTimeoutDeadline = deadline;
        // Small buffer so the deadline has definitely passed when we check
        long delayMs = Math.max(0, deadline - System.currentTimeMillis()) + 500L;
        LOGGER.fine("Scheduling timeout check in " + delayMs + "ms");
        nextTimeoutTask = jenkins.util.Timer.get()
                .schedule(
                        () -> {
                            LOGGER.fine("Scheduled timeout check fired");
                            synchronized (syncResources) {
                                nextTimeoutDeadline = 0;
                                nextTimeoutTask = null;
                                while (proceedNextContext()) {
                                    // process as many contexts as possible
                                }
                            }
                        },
                        delayMs,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------------------
    private AtomicBoolean getSavePending() {
        AtomicBoolean sp = savePending;
        if (sp == null) {
            synchronized (this) {
                sp = savePending;
                if (sp == null) {
                    savePending = sp = new AtomicBoolean(false);
                }
            }
        }
        return sp;
    }

    private ScheduledExecutorService getSaveExecutor() {
        ScheduledExecutorService se = saveExecutor;
        if (se == null) {
            synchronized (this) {
                se = saveExecutor;
                if (se == null) {
                    saveExecutor = se = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "lockable-resources-async-save");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        return se;
    }

    @Override
    public void save() {
        if (enableSave == -1) {
            // read system property and cache it.
            enableSave = SystemProperties.getBoolean(Constants.SYSTEM_PROPERTY_DISABLE_SAVE) ? 0 : 1;
        }

        if (enableSave == 0) return; // saving is disabled

        if (BulkChange.contains(this)) return;

        if (asyncSaveEnabled && saveCoalesceMs > 0) {
            if (getSavePending().compareAndSet(false, true)) {
                getSaveExecutor().schedule(this::doSave, saveCoalesceMs, TimeUnit.MILLISECONDS);
            }
        } else {
            doSave();
        }
    }

    private void doSave() {
        getSavePending().set(false);
        synchronized (syncResources) {
            try {
                getConfigFile().write(this);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to save " + getConfigFile(), e);
            }
        }
    }

    // ---------------------------------------------------------------------------
    /**
     * Flush any pending async save during Jenkins shutdown so that lock state
     * is never lost on an orderly restart.
     */
    @Terminator
    public static void flushPendingSave() {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        ScheduledExecutorService se = lrm.saveExecutor;
        if (se != null) {
            se.shutdownNow();
        }
        if (lrm.savePending != null && lrm.savePending.compareAndSet(true, false)) {
            lrm.doSave();
            LOGGER.fine("Flushed pending async save during shutdown");
        }
    }

    // ---------------------------------------------------------------------------
    /** For testing purpose. */
    @Restricted(NoExternalUse.class)
    public LockableResource getFirst() {
        return this.getResources().get(0);
    }
}
