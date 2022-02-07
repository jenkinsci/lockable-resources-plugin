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
import hudson.BulkChange;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
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
import net.sf.json.JSONObject;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesCandidatesStruct;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkins.plugins.lockableresources.queue.QueuedContextStruct;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

@Extension
public class LockableResourcesManager extends GlobalConfiguration {

  /** @deprecated Leftover of queue sorter support (since 1.7) */
  @Deprecated private transient int defaultPriority;
  /** @deprecated Leftover of queue sorter support (since 1.7) */
  @Deprecated private transient String priorityParameterName;

  private List<LockableResource> resources;
  private transient Cache<Long,List<LockableResource>> cachedCandidates = CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();

  /**
   * Only used when this lockable resource is tried to be locked by {@link LockStep}, otherwise
   * (freestyle builds) regular Jenkins queue is used.
   */
  private List<QueuedContextStruct> queuedContexts = new ArrayList<>();

  public LockableResourcesManager() {
    resources = new ArrayList<>();
    load();
  }

  public List<LockableResource> getResources() {
    return resources;
  }

  public synchronized List<LockableResource> getDeclaredResources() {
    ArrayList<LockableResource> declaredResources = new ArrayList<>();
    for (LockableResource r : resources) {
      if (!r.isEphemeral()) {
        declaredResources.add(r);
      }
    }
    return declaredResources;
  }

  @DataBoundSetter
  public synchronized void setDeclaredResources(List<LockableResource> declaredResources) {
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

  public List<LockableResource> getResourcesFromProject(String fullName) {
    List<LockableResource> matching = new ArrayList<>();
    for (LockableResource r : resources) {
      String rName = r.getQueueItemProject();
      if (rName != null && rName.equals(fullName)) {
        matching.add(r);
      }
    }
    return matching;
  }

  public List<LockableResource> getResourcesFromBuild(Run<?, ?> build) {
    List<LockableResource> matching = new ArrayList<>();
    for (LockableResource r : resources) {
      Run<?, ?> rBuild = r.getBuild();
      if (rBuild != null && rBuild == build) {
        matching.add(r);
      }
    }
    return matching;
  }

  public Boolean isValidLabel(String label) {
    return this.getAllLabels().contains(label);
  }

  public Set<String> getAllLabels() {
    Set<String> labels = new HashSet<>();
    for (LockableResource r : this.resources) {
      String rl = r.getLabels();
      if (rl == null || "".equals(rl)) continue;
      labels.addAll(Arrays.asList(rl.split("\\s+")));
    }
    return labels;
  }

  public int getFreeResourceAmount(String label) {
    int free = 0;
    for (LockableResource r : this.resources) {
      if (r.isLocked() || r.isQueued() || r.isReserved()) continue;
      if (Arrays.asList(r.getLabels().split("\\s+")).contains(label)) free += 1;
    }
    return free;
  }

  public List<LockableResource> getResourcesWithLabel(String label, Map<String, Object> params) {
    List<LockableResource> found = new ArrayList<>();
    for (LockableResource r : this.resources) {
      if (r.isValidLabel(label, params)) found.add(r);
    }
    return found;
  }

  /**
   * Get a list of resources matching the script.
   *
   * @param script Script
   * @param params Additional parameters
   * @return List of the matching resources
   * @throws ExecutionException Script execution failed for one of the resources. It is considered
   *     as a fatal failure since the requirement list may be incomplete
   * @since 2.0
   */
  @NonNull
  public List<LockableResource> getResourcesMatchingScript(
    @NonNull SecureGroovyScript script, @CheckForNull Map<String, Object> params)
    throws ExecutionException {
    List<LockableResource> found = new ArrayList<>();
    for (LockableResource r : this.resources) {
      if (r.scriptMatches(script, params)) found.add(r);
    }
    return found;
  }

  public synchronized LockableResource fromName(String resourceName) {
    if (resourceName != null) {
      for (LockableResource r : resources) {
        if (resourceName.equals(r.getName())) return r;
      }
    }
    return null;
  }

  public synchronized boolean queue(
    List<LockableResource> resources,
    long queueItemId,
    String queueProjectName
  ) {
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

  /**
   * @deprecated USe {@link
   *     #tryQueue(org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct, long,
   *     java.lang.String, int, java.util.Map, java.util.logging.Logger)}
   */
  @Deprecated
  @CheckForNull
  public synchronized List<LockableResource> queue(
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

  /**
   * If the lockable resource availability was evaluated before and
   * cached to avoid frequent re-evaluations under queued pressure
   * when there are no resources to give, we should state that a
   * resource is again instantly available for re-evaluation when
   * we know it was busy and right now is being freed.
   * Note that a resource may be (both or separately) locked by a
   * build and/or reserved by a user (or stolen from build to user)
   * so we only un-cache it here if it becomes completely available.
   * Called as a helper from methods that unlock/unreserve/reset
   * (or indirectly - recycle) stuff.
   *
   * NOTE for people using LR or LRM methods directly to add some
   * abilities in their pipelines that are not provided by plugin:
   * the `cachedCandidates` is an LRM concept, so if you tell a
   * resource (LR instance) directly to unlock/unreserve, it has
   * no idea to clean itself from this cache, and may be considered
   * busy in queuing for some time afterwards.
   */
  public synchronized boolean uncacheIfFreeing(LockableResource candidate, boolean unlocking, boolean unreserving) {
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
        if (candidates.size() < 2) {
          // Nothing is there, or would be after removing the one entry
          cachedCandidates.invalidate(queueItemId);
        } else {
          // Reduce the referenced list
          candidates.remove(candidate);
        }
      }
    }

    return true;
  }

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
  public synchronized List<LockableResource> tryQueue(
    LockableResourcesStruct requiredResources,
    long queueItemId,
    String queueItemProject,
    int number,
    Map<String, Object> params,
    Logger log)
    throws ExecutionException {
    List<LockableResource> selected = new ArrayList<>();

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

    if (candidatesByScript ||
      (requiredResources.label != null && !requiredResources.label.isEmpty())) {
      candidates = cachedCandidates.getIfPresent(queueItemId);
      if (candidates != null) {
        candidates.retainAll(resources);
      } else {
        candidates = (systemGroovyScript == null)
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
      for (LockableResource x : resources) {
        if (x.getQueueItemProject() != null && x.getQueueItemProject().equals(queueItemProject))
          x.unqueue();
      }
      return null;
    }

    for (LockableResource rsc : selected) {
      rsc.setQueued(queueItemId, queueItemProject);
    }
    return selected;
  }

  // Adds already selected (in previous queue round) resources to 'selected'
  // Return false if another item queued for this project -> bail out
  private boolean checkCurrentResourcesStatus(
    List<LockableResource> selected, String project, long taskId, Logger log) {
    for (LockableResource r : resources) {
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
            "{0} has another build " + "that already queued resource {1}. Continue queueing.",
            new Object[] {project, r});
          return false;
        }
      }
    }
    return true;
  }

  public synchronized boolean lock(
    Set<LockableResource> resources, Run<?, ?> build, @Nullable StepContext context) {
    return lock(resources, build, context, null, null, false);
  }

  /** Try to lock the resource and return true if locked. */
  public synchronized boolean lock(
    Set<LockableResource> resources,
    Run<?, ?> build,
    @Nullable StepContext context,
    @Nullable String logmessage,
    final String variable,
    boolean inversePrecedence) {
    boolean needToWait = false;

    for (LockableResource r : resources) {
      if (r.isReserved() || r.isLocked()) {
        needToWait = true;
        break;
      }
    }
    if (!needToWait) {
      for (LockableResource r : resources) {
        r.unqueue();
        r.setBuild(build);
      }
      if (context != null) {
        // since LockableResource contains transient variables, they cannot be correctly serialized
        // hence we use their unique resource names and properties
        LinkedHashMap<String, List<LockableResourceProperty>> resourceNames = new LinkedHashMap<>();
        for (LockableResource resource : resources) {
          resourceNames.put(resource.getName(), resource.getProperties());
        }
        LockStepExecution.proceed(resourceNames, context, logmessage, variable, inversePrecedence);
      }
      save();
    }
    return !needToWait;
  }

  private synchronized void freeResources(
    List<String> unlockResourceNames, @Nullable Run<?, ?> build) {
    for (String unlockResourceName : unlockResourceNames) {
      Iterator<LockableResource> resourceIterator = this.resources.iterator();
      while (resourceIterator.hasNext()) {
        LockableResource resource = resourceIterator.next();
        if (resource != null
          && resource.getName() != null
          && resource.getName().equals(unlockResourceName)) {
          if (build == null
            || (resource.getBuild() != null
            && build
            .getExternalizableId()
            .equals(resource.getBuild().getExternalizableId()))) {
            // No more contexts, unlock resource
            resource.unqueue();
            resource.setBuild(null);
            uncacheIfFreeing(resource, true, false);
            if (resource.isEphemeral()) {
              resourceIterator.remove();
            }
          }
        }
      }
    }
  }

  public synchronized void unlock(
    List<LockableResource> resourcesToUnLock,
    @Nullable Run<?, ?> build
  ) {
    unlock(resourcesToUnLock, build, false);
  }

  public synchronized void unlock(
    @Nullable List<LockableResource> resourcesToUnLock,
    @Nullable Run<?, ?> build,
    boolean inversePrecedence) {
    List<String> resourceNamesToUnLock = new ArrayList<>();
    if (resourcesToUnLock != null) {
      for (LockableResource r : resourcesToUnLock) {
        resourceNamesToUnLock.add(r.getName());
      }
    }

    this.unlockNames(resourceNamesToUnLock, build, inversePrecedence);
  }

  public synchronized void unlockNames(
    @Nullable List<String> resourceNamesToUnLock,
    @Nullable Run<?, ?> build,
    boolean inversePrecedence) {
    // make sure there is a list of resource names to unlock
    if (resourceNamesToUnLock == null || resourceNamesToUnLock.isEmpty()) {
      return;
    }

    // process as many contexts as possible
    List<String> remainingResourceNamesToUnLock = new ArrayList<>(resourceNamesToUnLock);

    QueuedContextStruct nextContext = null;
    while (!remainingResourceNamesToUnLock.isEmpty()) {
      // check if there are resources which can be unlocked (and shall not be unlocked)
      nextContext =
        this.getNextQueuedContext(remainingResourceNamesToUnLock, inversePrecedence, nextContext);

      // no context is queued which can be started once these resources are free'd.
      if (nextContext == null) {
        this.freeResources(remainingResourceNamesToUnLock, build);
        save();
        return;
      }

      Set<LockableResource> requiredResourceForNextContext =
        checkResourcesAvailability(
          nextContext.getResources(), null, remainingResourceNamesToUnLock);

      // resourceNamesToUnlock contains the names of the previous resources.
      // requiredResourceForNextContext contains the resource objects which are required for the
      // next context.
      // It is guaranteed that there is an overlap between the two - the resources which are to be
      // reused.
      boolean needToWait = false;
      for (LockableResource requiredResource : requiredResourceForNextContext) {
        if (requiredResource.isStolen()) {
          needToWait = true;
          break;
        }
        if (!remainingResourceNamesToUnLock.contains(requiredResource.getName())) {
          if (requiredResource.isReserved() || requiredResource.isLocked()) {
            needToWait = true;
            break;
          }
        }
      }

      if (!needToWait) {
        // remove context from queue and process it
        unqueueContext(nextContext.getContext());

        LinkedHashMap<String, List<LockableResourceProperty>> resourcesToLock = new LinkedHashMap<>();

        // lock all (old and new resources)
        for (LockableResource requiredResource : requiredResourceForNextContext) {
          try {
            requiredResource.setBuild(nextContext.getContext().get(Run.class));
            resourcesToLock.put(requiredResource.getName(), requiredResource.getProperties());
          } catch (Exception e) {
            // skip this context, as the build cannot be retrieved (maybe it was deleted while
            // running?)
            LOGGER.log(
              Level.WARNING,
              "Skipping queued context for lock. Cannot get the Run object from the context to "
                + "proceed with lock; this could be a legitimate state if the build waiting "
                + "for the lock was deleted or hard killed. More information is logged at "
                + "Level.FINE for debugging purposes.");
            LOGGER.log(
              Level.FINE, "Cannot get the Run object from the context to proceed with lock", e);
            unlockNames(remainingResourceNamesToUnLock, build, inversePrecedence);
            return;
          }
        }

        // determine old resources no longer needed
        List<String> freeResources = new ArrayList<>();
        for (String resourceNameToUnlock : remainingResourceNamesToUnLock) {
          boolean resourceStillNeeded = false;
          for (LockableResource requiredResource : requiredResourceForNextContext) {
            if (resourceNameToUnlock != null
              && resourceNameToUnlock.equals(requiredResource.getName())) {
              resourceStillNeeded = true;
              break;
            }
          }

          if (!resourceStillNeeded) {
            freeResources.add(resourceNameToUnlock);
          }
        }

        // keep unused resources
        remainingResourceNamesToUnLock.retainAll(freeResources);

        // continue with next context
        LockStepExecution.proceed(
          resourcesToLock,
          nextContext.getContext(),
          nextContext.getResourceDescription(),
          nextContext.getVariableName(),
          inversePrecedence);
      }
    }
    save();
  }

  /** @see #getNextQueuedContext(List, List, boolean, QueuedContextStruct) */
  @CheckForNull
  private QueuedContextStruct getNextQueuedContext(
    List<String> resourceNamesToUnLock,
    boolean inversePrecedence,
    QueuedContextStruct from
  ) {
    return this.getNextQueuedContext(resourceNamesToUnLock, null, inversePrecedence, from);
  }

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
    @Nullable List<String> resourceNamesToUnLock,
    @Nullable List<String> resourceNamesToUnReserve,
    boolean inversePrecedence,
    QueuedContextStruct from
  ) {
    QueuedContextStruct newestEntry = null;
    int fromIndex = from != null ? this.queuedContexts.indexOf(from) + 1 : 0;
    if (!inversePrecedence) {
      for (int i = fromIndex; i < this.queuedContexts.size(); i++) {
        QueuedContextStruct entry = this.queuedContexts.get(i);
        if (checkResourcesAvailability(
                entry.getResources(), null, resourceNamesToUnLock, resourceNamesToUnReserve)
            != null) {
          return entry;
        }
      }
    } else {
      long newest = 0;
      List<QueuedContextStruct> orphan = new ArrayList<>();
      for (int i = fromIndex; i < this.queuedContexts.size(); i++) {
        QueuedContextStruct entry = this.queuedContexts.get(i);
        if (checkResourcesAvailability(
                entry.getResources(), null, resourceNamesToUnLock, resourceNamesToUnReserve)
            != null) {
          try {
            Run<?, ?> run = entry.getContext().get(Run.class);
            if (run != null && run.getStartTimeInMillis() > newest) {
              newest = run.getStartTimeInMillis();
              newestEntry = entry;
            }
          } catch (IOException | InterruptedException e) {
            // skip this one, for some reason there is no Run object for this context
            orphan.add(entry);
          }
        }
      }
      if (!orphan.isEmpty()) {
        this.queuedContexts.removeAll(orphan);
      }
    }

    return newestEntry;
  }

  /** Creates the resource if it does not exist. */
  public synchronized boolean createResource(String name) {
    if (name != null) {
      LockableResource existent = fromName(name);
      if (existent == null) {
        LockableResource resource = new LockableResource(name);
        resource.setEphemeral(true);
        getResources().add(resource);
        save();
        return true;
      }
    }
    return false;
  }

  public synchronized boolean createResourceWithLabel(String name, String label) {
    if (name != null && label != null) {
      LockableResource existent = fromName(name);
      if (existent == null) {
        LockableResource resource = new LockableResource(name);
        resource.setLabels(label);
        getResources().add(resource);
        save();
        return true;
      }
    }
    return false;
  }

  public synchronized boolean createResourceWithLabelAndProperties(String name, String label, Map<String, String> properties) {
    if (name != null && label != null && properties != null) {
      LockableResource existent = fromName(name);
      if (existent == null) {
        LockableResource resource = new LockableResource(name);
        resource.setLabels(label);
        resource.setProperties(
          properties.entrySet().stream().map(e -> {
            LockableResourceProperty p = new LockableResourceProperty();
            p.setName(e.getKey());
            p.setValue(e.getValue());
            return p;
          }).collect(Collectors.toList()));
        getResources().add(resource);
        save();
        return true;
      }
    }
    return false;
  }

  /**
   * Reserves an available resource for the userName indefinitely (until that person, or some
   * explicit scripted action, decides to release the resource).
   */
  public synchronized boolean reserve(
    List<LockableResource> resources,
    String userName
  ) {
    for (LockableResource r : resources) {
      if (r.isReserved() || r.isLocked() || r.isQueued()) {
        return false;
      }
    }
    for (LockableResource r : resources) {
      r.reserve(userName);
    }
    save();
    return true;
  }

  /**
   * Reserves a resource that may be or not be locked by some
   * job (or reserved by some user) already, giving it away to
   * the userName indefinitely (until that person, or some
   * explicit scripted action, later decides to release the
   * resource).
   */
  public synchronized boolean steal(
    List<LockableResource> resources,
    String userName
  ) {
    for (LockableResource r : resources) {
      r.setReservedBy(userName);
      r.setStolen();
    }
    unlock(resources, null, false);
    save();
    return true;
  }

  /**
   * Reserves a resource that may be or not be reserved by some person already, giving it away to
   * the userName indefinitely (until that person, or some explicit scripted action, decides to
   * release the resource).
   */
  public synchronized void reassign(
    List<LockableResource> resources,
    String userName
  ) {
    for (LockableResource r : resources) {
      if (r.isReserved() || r.isLocked() || r.isQueued()) {
        r.unReserve();
      }
      r.setReservedBy(userName);
    }
    save();
  }

  private void unreserveResources(@NonNull List<LockableResource> resources) {
    for (LockableResource l : resources) {
      uncacheIfFreeing(l, false, true);
      l.unReserve();
    }
    save();
  }

  public synchronized void unreserve(List<LockableResource> resources) {
    // make sure there is a list of resources to unreserve
    if (resources == null || resources.isEmpty()) {
      return;
    }
    List<String> resourceNamesToUnreserve = new ArrayList<>();
    for (LockableResource r : resources) {
      resourceNamesToUnreserve.add(r.getName());
    }

    // check if there are resources which can be unlocked (and shall not be unlocked)
    QueuedContextStruct nextContext =
      this.getNextQueuedContext(null, resourceNamesToUnreserve, false, null);

    // no context is queued which can be started once these resources are free'd.
    if (nextContext == null) {
      LOGGER.log(
        Level.FINER,
        () ->
          "No context queued for resources "
            + String.join(", ", resourceNamesToUnreserve)
            + " so unreserving and proceeding.");
      unreserveResources(resources);
      return;
    }

    PrintStream nextContextLogger = null;
    try {
      TaskListener nextContextTaskListener = nextContext.getContext().get(TaskListener.class);
      if (nextContextTaskListener != null) {
        nextContextLogger = nextContextTaskListener.getLogger();
      }
    } catch (IOException | InterruptedException e) {
      LOGGER.log(Level.FINE, "Could not get logger for next context: " + e, e);
    }

    // remove context from queue and process it
    Set<LockableResource> requiredResourceForNextContext =
      checkResourcesAvailability(
        nextContext.getResources(), nextContextLogger,
        null, resourceNamesToUnreserve);
    this.queuedContexts.remove(nextContext);

    // resourceNamesToUnreserve contains the names of the previous resources.
    // requiredResourceForNextContext contains the resource objects which are required for the next
    // context.
    // It is guaranteed that there is an overlap between the two - the resources which are to be
    // reused.
    boolean needToWait = false;
    for (LockableResource requiredResource : requiredResourceForNextContext) {
      if (!resourceNamesToUnreserve.contains(requiredResource.getName())) {
        if (requiredResource.isReserved() || requiredResource.isLocked()) {
          needToWait = true;
          break;
        }
      }
    }

    if (needToWait) {
      unreserveResources(resources);
      return;
    } else {
      unreserveResources(resources);
      LinkedHashMap<String, List<LockableResourceProperty>> resourcesToLock = new LinkedHashMap<>();

      // lock all (old and new resources)
      for (LockableResource requiredResource : requiredResourceForNextContext) {
        try {
          requiredResource.setBuild(nextContext.getContext().get(Run.class));
          resourcesToLock.put(requiredResource.getName(), requiredResource.getProperties());
        } catch (Exception e) {
          // skip this context, as the build cannot be retrieved (maybe it was deleted while
          // running?)
          LOGGER.log(
            Level.WARNING,
            "Skipping queued context for lock. Cannot get the Run object from the context to "
              + "proceed with lock; this could be a legitimate state if the build waiting for "
              + "the lock was deleted or hard killed. More information is logged at "
              + "Level.FINE for debugging purposes.");
          LOGGER.log(
            Level.FINE, "Cannot get the Run object from the context to proceed with lock", e);
          return;
        }
      }

      // continue with next context
      LockStepExecution.proceed(
        resourcesToLock,
        nextContext.getContext(),
        nextContext.getResourceDescription(),
        nextContext.getVariableName(),
        false);
    }
    save();
  }

  @NonNull
  @Override
  public String getDisplayName() {
    return "External Resources";
  }

  public synchronized void reset(List<LockableResource> resources) {
    for (LockableResource r : resources) {
      uncacheIfFreeing(r, true, true);
      r.reset();
    }
    save();
  }

  /**
   * Make the lockable resource re-usable and notify the queue(s), if any WARNING: Do not use this
   * from inside the lock step closure which originally locked this resource, to avoid nasty
   * surprises! Namely, this *might* let a second consumer use the resource quickly, but when the
   * original closure ends and unlocks again that resource, a third consumer might then effectively
   * hijack it from the second one.
   */
  public synchronized void recycle(List<LockableResource> resources) {
    // Not calling reset() because that also un-queues the resource
    // and we want to proclaim it is usable (if anyone is waiting)
    this.unlock(resources, null);
    this.unreserve(resources);
  }

  @Override
  public boolean configure(StaplerRequest req, JSONObject json) {
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

    return true;
  }

  /** @see #checkResourcesAvailability(List, PrintStream, List, List, boolean) */
  public synchronized Set<LockableResource> checkResourcesAvailability(
    List<LockableResourcesStruct> requiredResourcesList,
    @Nullable PrintStream logger,
    @Nullable List<String> lockedResourcesAboutToBeUnlocked) {
    boolean skipIfLocked = false;
    return this.checkResourcesAvailability(
      requiredResourcesList, logger, lockedResourcesAboutToBeUnlocked, null, skipIfLocked);
  }

  /** @see #checkResourcesAvailability(List, PrintStream, List, List, boolean) */
  public synchronized Set<LockableResource> checkResourcesAvailability(
      List<LockableResourcesStruct> requiredResourcesList,
      @Nullable PrintStream logger,
      @Nullable List<String> lockedResourcesAboutToBeUnlocked,
      boolean skipIfLocked) {
    return this.checkResourcesAvailability(
      requiredResourcesList, logger, lockedResourcesAboutToBeUnlocked, null, skipIfLocked);
  }

  /** @see #checkResourcesAvailability(List, PrintStream, List, List, boolean) */
  public synchronized Set<LockableResource> checkResourcesAvailability(
    List<LockableResourcesStruct> requiredResourcesList,
    @Nullable PrintStream logger,
    @Nullable List<String> lockedResourcesAboutToBeUnlocked,
    @Nullable List<String> reservedResourcesAboutToBeUnreserved) {
    boolean skipIfLocked = false;
    return this.checkResourcesAvailability(
      requiredResourcesList,
      logger,
      lockedResourcesAboutToBeUnlocked,
      reservedResourcesAboutToBeUnreserved,
      skipIfLocked);
  }

  /**
   * Checks if there are enough resources available to satisfy the requirements specified within
   * requiredResources and returns the necessary available resources. If not enough resources are
   * available, returns null.
   */
  public synchronized Set<LockableResource> checkResourcesAvailability(
    List<LockableResourcesStruct> requiredResourcesList,
    @Nullable PrintStream logger,
    @Nullable List<String> lockedResourcesAboutToBeUnlocked,
    @Nullable List<String> reservedResourcesAboutToBeUnreserved,
    boolean skipIfLocked) {

    List<LockableResourcesCandidatesStruct> requiredResourcesCandidatesList = new ArrayList<>();

    // Build possible resources for each requirement
    for (LockableResourcesStruct requiredResources : requiredResourcesList) {
      // get possible resources
      int requiredAmount = 0; // 0 means all
      List<LockableResource> candidates = new ArrayList<>();
      if (requiredResources.label != null && requiredResources.label.isEmpty()) {
        candidates.addAll(requiredResources.required);
      } else {
        candidates.addAll(getResourcesWithLabel(requiredResources.label, null));
        if (requiredResources.requiredNumber != null) {
          try {
            requiredAmount = Integer.parseInt(requiredResources.requiredNumber);
          } catch (NumberFormatException e) {
            requiredAmount = 0;
          }
        }
      }

      if (requiredAmount == 0) {
        requiredAmount = candidates.size();
      }

      requiredResourcesCandidatesList.add(
        new LockableResourcesCandidatesStruct(candidates, requiredAmount));
    }

    // Process freed resources
    int totalSelected = 0;
    // These resources are currently reserved, even though candidates
    // for freeing (might be reserved inside lock step "bypassing" the
    // lockable resources general logic). They may become available
    // later and we want to notice that - so they are not selected
    // now, but we do not bail out and end the looping either.
    int totalReserved = 0;

    for (LockableResourcesCandidatesStruct requiredResources : requiredResourcesCandidatesList) {
      // start with an empty set of selected resources
      List<LockableResource> selected = new ArrayList<>();

      // some resources might be already locked, but will be freed.
      // Determine if these resources can be reused
      // FIXME? Why is this check not outside the for loop?
      if (lockedResourcesAboutToBeUnlocked != null
        ||  reservedResourcesAboutToBeUnreserved != null
      ) {
        for (LockableResource candidate : requiredResources.candidates) {
          if (selected.size() >= requiredResources.requiredAmount) {
            break;
          }

          String candidateName = candidate.getName();
          boolean listedUnlock =
              (lockedResourcesAboutToBeUnlocked != null
                  && lockedResourcesAboutToBeUnlocked.contains(candidateName));
          boolean listedUnreserve =
              (reservedResourcesAboutToBeUnreserved != null
                  && reservedResourcesAboutToBeUnreserved.contains(candidateName));
          boolean isReserved = candidate.isReserved();
          boolean isLocked = candidate.isLocked();

          if (isReserved) {
            if (listedUnreserve) {
              if (!isLocked || listedUnlock) {
                // Avoid selecting a reserved candidate which *is* also locked
                // and not listed for imminent un-locking
                selected.add(candidate);
              }
            } else {
              // Caller did not say that this resource will be un-reserved now!
              // Still needed, might be `lr.setReservedBy()` from the lock step
              // closure by users who deemed that required in their workflow
              // and might need to free it manually - maybe after postmortem.
              // Note that such un-reservation should go through LRM API,
              // as `lrm.unreserve([lr])`, and not just `lr.setReservedBy(null)`,
              // (nor `lrm.reset([lr])`) to get into this method among others
              // and let the resource be instantly re-used by someone from an
              // already waiting queue. Otherwise those already waiting are not
              // notified until you lock/unlock that resource again.
              if (logger != null) {
                logger.println(
                  "Candidate resource '"
                    + candidateName
                    + "' is reserved by '"
                    + candidate.getReservedBy()
                    + "', not treating as available.");
              }
              totalReserved += 1;
              continue;
            }
          } else {
            // If the resource is not reserved (as checked above)
            // but listed for releasing in either category, select it
            if (listedUnlock || listedUnreserve) {
              selected.add(candidate);
            }
          }
        }
      }

      totalSelected += selected.size();
      requiredResources.selected = selected;
    }

    // if none of the currently locked resources can be reused,
    // this context is not suitable to be continued with
    // Note that if arguments lockedResourcesAboutToBeUnlocked==null
    // and reservedResourcesAboutToBeUnreserved==null, then
    // the loop above was effectively skipped
    if (totalSelected == 0
        && totalReserved == 0
        && (lockedResourcesAboutToBeUnlocked != null
            || reservedResourcesAboutToBeUnreserved != null)
    ) {
      return null;
    }

    // Find remaining resources
    Set<LockableResource> allSelected = new HashSet<>();

    for (LockableResourcesCandidatesStruct requiredResources : requiredResourcesCandidatesList) {
      List<LockableResource> candidates = requiredResources.candidates;
      List<LockableResource> selected = requiredResources.selected;
      int requiredAmount = requiredResources.requiredAmount;

      // Try and re-use as many previously selected resources first
      List<LockableResource> alreadySelectedCandidates = new ArrayList<>(candidates);
      alreadySelectedCandidates.retainAll(allSelected);
      for (LockableResource rs : alreadySelectedCandidates) {
        if (selected.size() >= requiredAmount) {
          break;
        }
        if (!rs.isReserved() && !rs.isLocked()) {
          selected.add(rs);
        }
      }

      candidates.removeAll(alreadySelectedCandidates);
      for (LockableResource rs : candidates) {
        if (selected.size() >= requiredAmount) {
          break;
        }
        if (!rs.isReserved() && !rs.isLocked()) {
          selected.add(rs);
        }
      }

      if (selected.size() < requiredAmount) {
        // Note: here we are looping over requiredResourcesCandidatesList
        // based on original argument requiredResourcesList with its specs
        // (maybe several) of required resources and their amounts.
        // As soon as we know we can not fulfill the overall requirement
        // (not enough of something from that list), we bail out quickly.
        if (logger != null && !skipIfLocked) {
          logger.println(
            "Found "
              + selected.size()
              + " available resource(s). Waiting for correct amount: "
              + requiredAmount
              + ".");
        }
        return null;
      }

      allSelected.addAll(selected);
    }

    return allSelected;
  }

  /*
   * Adds the given context and the required resources to the queue if
   * this context is not yet queued.
   */
  public synchronized void queueContext(
    StepContext context,
    List<LockableResourcesStruct> requiredResources,
    String resourceDescription,
    String variableName) {
    for (QueuedContextStruct entry : this.queuedContexts) {
      if (entry.getContext() == context) {
        return;
      }
    }

    this.queuedContexts.add(
      new QueuedContextStruct(context, requiredResources, resourceDescription, variableName));
    save();
  }

  public synchronized boolean unqueueContext(StepContext context) {
    for (Iterator<QueuedContextStruct> iter = this.queuedContexts.listIterator();
      iter.hasNext(); ) {
      if (iter.next().getContext() == context) {
        iter.remove();
        save();
        return true;
      }
    }
    return false;
  }

  public static LockableResourcesManager get() {
    return (LockableResourcesManager)
      Jenkins.get().getDescriptorOrDie(LockableResourcesManager.class);
  }

  @Override
  public synchronized void save() {
    if (BulkChange.contains(this)) return;

    try {
      getConfigFile().write(this);
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "Failed to save " + getConfigFile(), e);
    }
  }

  private static final Logger LOGGER = Logger.getLogger(LockableResourcesManager.class.getName());
}
