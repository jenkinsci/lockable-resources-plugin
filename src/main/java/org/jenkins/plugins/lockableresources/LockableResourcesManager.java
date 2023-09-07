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
  @SuppressFBWarnings(
      value = "NP_LOAD_OF_KNOWN_NULL_VALUE",
      justification = "null value is checked correctly")
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
  /** Get amount of free resources contained given *label* */
  @NonNull
  @Restricted(NoExternalUse.class)
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
   */
  @Deprecated
  @Restricted(NoExternalUse.class)
  @ExcludeFromJacocoGeneratedReport
  public List<LockableResource> getResourcesWithLabel(String label, Map<String, Object> params) {
    return getResourcesWithLabel(label);
  }

  // ---------------------------------------------------------------------------
  /**
   * Returns resources matching by given *label*. Note: The param *params* is not used (has no
   * effect)
   */
  @NonNull
  @Restricted(NoExternalUse.class)
  public List<LockableResource> getResourcesWithLabel(String label) {
    List<LockableResource> found = new ArrayList<>();
    label = Util.fixEmpty(label);

    if (label == null) {
      return found;
    }

    for (LockableResource r : this.getReadOnlyResources()) {
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
      @NonNull SecureGroovyScript script, @CheckForNull Map<String, Object> params)
      throws ExecutionException {
    List<LockableResource> found = new ArrayList<>();
    for (LockableResource r : this.getReadOnlyResources()) {
      if (r.scriptMatches(script, params)) found.add(r);
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
  private List<LockableResource> fromNames(List<String> names) {
    List<LockableResource> list = new ArrayList<>();
    for(String name : names) {
      LockableResource r = this.fromName(name);
      if (r != null) // this is probably bug, but nobody know
        list.add(r);
    }
    return list;
  }

  // ---------------------------------------------------------------------------
  private String getStack() {
    StringBuffer buf = new StringBuffer();
    for (StackTraceElement st : Thread.currentThread().getStackTrace()) {
      buf.append("\n" + st);
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
  public boolean queue(
      List<LockableResource> resources, long queueItemId, String queueProjectName) {
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
            Level.WARNING,
            "Failed to queue item " + itemName,
            ex.getCause() != null ? ex.getCause() : ex);
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
  public boolean uncacheIfFreeing(
      LockableResource candidate, boolean unlocking, boolean unreserving) {
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

      if (candidatesByScript
          || (requiredResources.label != null && !requiredResources.label.isEmpty())) {

        candidates = cachedCandidates.getIfPresent(queueItemId);
        if (candidates != null) {
          candidates.retainAll(this.resources);
        } else {
          candidates =
              (systemGroovyScript == null)
                  ? getResourcesWithLabel(requiredResources.label, params)
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
          if (x.getQueueItemProject() != null && x.getQueueItemProject().equals(queueItemProject))
            x.unqueue();
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
  /**
   * @deprecated use {@link
   * #boolean lock(
   *   List<LockableResource> resources,
   *   Run<?, ?> build)
   */
  @Deprecated
  public boolean lock(
      List<LockableResource> resources, Run<?, ?> build, @Nullable StepContext context) {
    return this.lock(resources, build);
  }

  // ---------------------------------------------------------------------------
  /**
   * @deprecated use {@link
   * #boolean lock(
   *   List<LockableResource> resources,
   *   Run<?, ?> build)
   */
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
  public boolean lock(
      List<LockableResource> resources,
      Run<?, ?> build) {

    LOGGER.fine("lock it: " + resources + " for build " + build);
    for (LockableResource r : resources) {
      if (r.isReserved() || r.isLocked()) {
        LOGGER.warning("lock() will fails, because " + r.getLockCause());
        return false; // not locked
      }
    }

    if (build == null) {
      LOGGER.warning("lock() will fails, because the build does not exits. " + resources);
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
  private void freeResources(List<LockableResource> unlockResources) {
    for (LockableResource resource : unlockResources) {
      // No more contexts, unlock resource
      resource.unqueue();
      resource.setBuild(null);
      uncacheIfFreeing(resource, true, false);

      if (resource.isEphemeral() != this.stillNeeded(resource)) {
        this.resources.remove(resource);
      }
    }
  }

  // ---------------------------------------------------------------------------
  /** Check if the resource is still needed (in the queue)
   */
  private boolean stillNeeded(LockableResource resource) {
    for (QueuedContextStruct entry : this.queuedContexts) {
      for(LockableResourcesStruct lrStruct : entry.getResources()) {
        List<LockableResource> required = lrStruct.required;
        if (required != null && required.contains(resource))
          return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------------------
  public void unlock(List<LockableResource> resourcesToUnLock, @Nullable Run<?, ?> build) {
    unlock(resourcesToUnLock, build, false);
  }

  // ---------------------------------------------------------------------------
  public void unlock(
      @Nullable List<LockableResource> resourcesToUnLock,
      @Nullable Run<?, ?> build,
      boolean inversePrecedence) {
    List<String> resourceNamesToUnLock =
        LockableResourcesManager.getResourcesNames(resourcesToUnLock);
    this.unlockNames(resourceNamesToUnLock, build, inversePrecedence);
  }

  // ---------------------------------------------------------------------------
  @SuppressFBWarnings(
      value = "REC_CATCH_EXCEPTION",
      justification = "not sure which exceptions might be catch.")
  public void unlockNames(
      @Nullable List<String> resourceNamesToUnLock,
      @Nullable Run<?, ?> build,
      boolean inversePrecedence) {
    // make sure there is a list of resource names to unlock
    if (resourceNamesToUnLock == null || resourceNamesToUnLock.isEmpty()) {
      return;
    }

    synchronized (this.syncResources) {
      LOGGER.info("unlockNames " + resourceNamesToUnLock + " from build: " + build);
      this.freeResources(fromNames(resourceNamesToUnLock));

      // process as many contexts as possible
      while (proceedNextContext(inversePrecedence));

      save();
    }
  }

  private boolean proceedNextContext(boolean inversePrecedence) {
    LOGGER.finest("inversePrecedence: " + inversePrecedence);
    QueuedContextStruct nextContext = this.getNextQueuedContext(inversePrecedence, null);
    LOGGER.finest("nextContext: " + nextContext);
    // no context is queued which can be started once these resources are free'd.
    if (nextContext == null) {
      LOGGER.info("no context is queued which can be started once these resources are free'd.");
      return false;
    }
    LOGGER.finest("nextContext candidates: " + nextContext.candidates);
    List<LockableResource> requiredResourceForNextContext = this.fromNames(nextContext.candidates);
    LOGGER.finest("nextContext real candidates: " + requiredResourceForNextContext);
    // remove context from queue and process it


    Run<?, ?> build = nextContext.getBuild();
    if (build == null) {
      // skip this context, as the build cannot be retrieved (maybe it was deleted while
      // running?)
      LOGGER.info("skip this context, as the build cannot be retrieved");
      return true;
    }
    boolean locked = this.lock(requiredResourceForNextContext, build); 
    if (!locked) {
      // defensive line, shall never happens
      LOGGER.warning("can not lock resources: " + requiredResourceForNextContext);
      return true;
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
        nextContext.getVariableName(),
        inversePrecedence);
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
  /**
   * Returns the next queued context with all its requirements satisfied.
   *
   * @param resourceNamesToUnLock resource names locked at the moment but available if required (as
   *     they are going to be unlocked soon)
   * @param resourceNamesToUnReserve resource names reserved at the moment but available if required
   *     (as they are going to be un-reserved soon)
   * @param inversePrecedence false pick up context as they are in the queue or true to take the
   *     most recent one (satisfying requirements)
   * @return the context or null
   */
  @CheckForNull
  private QueuedContextStruct getNextQueuedContext(
      boolean inversePrecedence,
      QueuedContextStruct from) {

    List<QueuedContextStruct> orphan = new ArrayList<>();
    QueuedContextStruct nextEntry = null;
    if (inversePrecedence) {
      // the last one added lock ist the newest one, and this must win
      for (int i = this.queuedContexts.size(); i >= 0; i--) {
        QueuedContextStruct entry = this.queuedContexts.get(i);
        // check queue list first
        if (!entry.isValid()) {
          orphan.add(entry);
          continue;
        }
        LOGGER.finest(
            "getNextQueuedContext: inversePrecedence - index: " + i + " " + entry);

        List<LockableResource> candidates = this.getAvailableResources(entry.getResources());
        if (candidates == null || candidates.isEmpty()) {
          continue;
        }

        entry.candidates = getResourcesNames(candidates);
        LOGGER.info("take this: " + entry);
        nextEntry = entry;
        break;
      }
    } else {
      // the fist one added lock ist the oldest one, and this must win
      for (int i = 0; i < this.queuedContexts.size(); i++) {
        QueuedContextStruct entry = this.queuedContexts.get(i);
        // check queue list first
        if (!entry.isValid()) {
          orphan.add(entry);
          continue;
        }
        LOGGER.info(
            "getNextQueuedContext: oldest win - index: " + i + " " + entry);

        List<LockableResource> candidates = this.getAvailableResources(entry.getResources());
        if (candidates == null || candidates.isEmpty()) {
          continue;
        }

        entry.candidates = getResourcesNames(candidates);
        LOGGER.info("take this: " + entry);
        nextEntry = entry;
        break;
      }
    }

    if (!orphan.isEmpty()) {
      this.queuedContexts.removeAll(orphan);
    }

    return nextEntry;
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
  public boolean createResource(String name) {
    name = Util.fixEmptyAndTrim(name);
    LockableResource resource = new LockableResource(name);
    resource.setEphemeral(true);

    return this.addResource(resource, /*doSave*/ true);
  }

  // ---------------------------------------------------------------------------
  public boolean createResourceWithLabel(String name, String label) {
    name = Util.fixEmptyAndTrim(name);
    label = Util.fixEmptyAndTrim(label);
    LockableResource resource = new LockableResource(name);
    resource.setLabels(label);

    return this.addResource(resource, /*doSave*/ true);
  }

  // ---------------------------------------------------------------------------
  public boolean createResourceWithLabelAndProperties(
      String name, String label, final Map<String, String> properties) {
    if (properties == null) {
      return false;
    }

    name = Util.fixEmptyAndTrim(name);
    label = Util.fixEmptyAndTrim(label);
    LockableResource resource = new LockableResource(name);
    resource.setLabels(label);
    resource.setProperties(
        properties.entrySet().stream()
            .map(
                e -> {
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
  public boolean addResource(final LockableResource resource) {
    return this.addResource(resource, false);
  }
  // ---------------------------------------------------------------------------
  public boolean addResource(final LockableResource resource, final boolean doSave) {

    synchronized (this.syncResources) {
      if (resource == null || resource.getName() == null || resource.getName().isEmpty()) {
        LOGGER.warning("Internal failure: We will add wrong resource: " + resource + getStack());
        return false;
      }
      if (this.resourceExist(resource.getName())) {
        LOGGER.fine("Internal failure: We will add existing resource: " + resource + getStack());
        return false;
      }
      LOGGER.fine("addResource: " + resource);
      this.resources.add(resource);
      if (doSave) {
        this.save();
      }
    }
    LOGGER.fine("resource added: " + resource);
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
  @SuppressFBWarnings(
      value = "REC_CATCH_EXCEPTION",
      justification = "not sure which exceptions might be catch.")
  public void unreserve(List<LockableResource> resources) {
    // make sure there is a list of resources to unreserve
    if (resources == null || resources.isEmpty()) {
      return;
    }

    synchronized (this.syncResources) {
      LOGGER.info("unreserve " + resources);
      unreserveResources(resources);

      proceedNextContext(false /*inversePrecedence*/);

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
        LOGGER.log(
            Level.WARNING, "Exception occurred while committing bulkchange operation.", exception);
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
  public List<LockableResource> getAvailableResources(
      final List<LockableResourcesStruct> requiredResourcesList) {
    return this.getAvailableResources(requiredResourcesList, null, null);
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

        available = this.getFreeResourcesWithLabel(requiredResources.label, requiredAmount, selectStrategy, logger, candidates);
      } else if (requiredResources.required != null) {
        available = requiredResources.required;
        // resource by name requested
        final boolean isPreReserved = !Collections.disjoint(candidates, available);
        String causes = isPreReserved ? String.format("The resource [%s] is pre-reserved by the same context. Check your extra filter", available) : this.getCauses(available);
        if (!causes.isEmpty()){
          // FIXME I think this is failure
          // Explain, You use filter label1 and it lock resource1 and then in extra you will lock resource1
          // But when I allow this line, many tests will fails, and I am pretty sure it will rice
          // exceptions on end-user pipelines 
          if (!isPreReserved)
            available = null;
          printLogs(causes, logger, isPreReserved ? Level.WARNING : Level.FINE);
        }
      } else {
        LOGGER.warning("getAvailableResources, Not implemented: " + requiredResources);
      }

      if (available == null || available.isEmpty()) {
        return null;
      }

      candidates.addAll(available);
    }

    return candidates;
  }

  // ---------------------------------------------------------------------------
  static private void printLogs(final String msg, final @Nullable PrintStream logger) {
    printLogs(msg, logger, Level.INFO);
  }

  // ---------------------------------------------------------------------------
  static private void printLogs(final String msg, final @Nullable PrintStream logger, final Level level) {
    if (logger != null)
      logger.println(msg);

    LOGGER.log(level, msg);
  }

  // ---------------------------------------------------------------------------
  @CheckForNull
  @Restricted(NoExternalUse.class)
  private List<LockableResource> getFreeResourcesWithLabel(
    @NonNull String label,
    long amount,
    final @Nullable ResourceSelectStrategy selectStrategy,
    final @Nullable PrintStream logger,
    final List<LockableResource> exclude
  ) {
    List<LockableResource> found = new ArrayList<>();

    final List<LockableResource> candidates = this.getResourcesWithLabel(label);
    candidates.removeAll(exclude);
    
    if (candidates.size() < amount) {
      printLogs("Found " + candidates.size() + " possible resource(s). Expected amount " + amount, logger);
      return null; // there are not enough resources
    }

    if (amount <= 0) {
      amount = candidates.size();
    }

    if (selectStrategy != null && selectStrategy.equals(ResourceSelectStrategy.RANDOM)) {
      Collections.shuffle(candidates);
    }

    for (LockableResource r : candidates) {
      if (r.isAvailable()) {
        found.add(r);
      }

      if (amount > 0 && found.size() >= amount) {
        return found;
      }
    }

    String msg =
        "Found "
            + found.size()
            + " available resource(s). Waiting for correct amount: "
            + amount
            + ".";
    if (SystemProperties.getBoolean(Constants.SYSTEM_PROPERTY_PRINT_LOCK_CAUSES)) {
      msg += "\nBlocked candidates: " + getCauses(candidates);
    }
    printLogs(msg, logger);

    return null;
  }

  // ---------------------------------------------------------------------------
  // for debug purpose
  private static String getCauses(List<LockableResource> resources) {
    StringBuffer buf = new StringBuffer();
    for (LockableResource resource : resources) {
      if (resource.isFree()) {
        continue;
      }
      String cause = resource.getLockCause();
      if (cause == null) cause = "  Unknown!!!";

      buf.append("\n  " + cause);
    }
    return buf.toString();
  }

  // ---------------------------------------------------------------------------
  /*
   * Adds the given context and the required resources to the queue if
   * this context is not yet queued.
   */
  public void queueContext(
      StepContext context,
      List<LockableResourcesStruct> requiredResources,
      String resourceDescription,
      String variableName) {
    synchronized (this.syncResources) {
      LOGGER.info("queueContext, " + requiredResources);
      for (QueuedContextStruct entry : this.queuedContexts) {
        if (entry.getContext() == context) {
          LOGGER.warning("queueContext, duplicated, " + requiredResources);
          return;
        }
      }

      this.queuedContexts.add(
          new QueuedContextStruct(context, requiredResources, resourceDescription, variableName));
      save();
    }
  }

  // ---------------------------------------------------------------------------
  public boolean unqueueContext(StepContext context) {
    synchronized (this.syncResources) {
      for (Iterator<QueuedContextStruct> iter = this.queuedContexts.listIterator();
          iter.hasNext(); ) {
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
    return (LockableResourcesManager)
        Jenkins.get().getDescriptorOrDie(LockableResourcesManager.class);
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
  /** Free all post mortem resources. */
  @Restricted(NoExternalUse.class)
  public void freePostMortemResources() {
    synchronized (this.syncResources) {
      LOGGER.log(Level.FINE, "lockable-resources-plugin free post mortem task run");
      for (LockableResource resource : this.resources) {
        if (resource.getBuild() != null && !resource.getBuild().isInProgress()) {
          LOGGER.log(
              Level.INFO,
              "lockable-resources-plugin reset resource "
                  + resource.getName()
                  + " due post mortem job: "
                  + resource.getBuildName());
          resource.recycle();
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  /**
   * Migrates "active" queuedContexts from LockableResource to LockableResourcesManager
   *
   * @deprecated Migration code for field introduced in 1.8 (since 1.11)
   */
  @Deprecated
  @ExcludeFromJacocoGeneratedReport
  public void compatibilityMigration() {
    synchronized (this.syncResources) {
      LOGGER.info(
          "lockable-resources-plugin compatibility migration task run for "
              + this.resources.size()
              + " resources");
      for (LockableResource resource : this.resources) {
        List<StepContext> queuedContexts = resource.getQueuedContexts();
        if (!queuedContexts.isEmpty()) {
          for (StepContext queuedContext : queuedContexts) {
            List<String> resourcesNames = new ArrayList<>();
            resourcesNames.add(resource.getName());
            LockableResourcesStruct resourceHolder =
                new LockableResourcesStruct(resourcesNames, "", 0);
            LockableResourcesManager.get()
                .queueContext(
                    queuedContext,
                    Collections.singletonList(resourceHolder),
                    resource.getName(),
                    null);
          }
          queuedContexts.clear();
        }
      }
    }
  }
}
