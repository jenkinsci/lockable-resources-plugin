/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import com.iwombat.util.GUIDUtil;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.BulkChange;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
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
        mergedResources.add(locked);
        continue;
      }
      mergedResources.add(r);
    }

    for (LockableResource r : lockedResources.values()) {
      // Removed locks became ephemeral.
      r.setDescription("");
      r.setLabels("");
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
  @Nonnull
  public List<LockableResource> getResourcesMatchingScript(
      @Nonnull SecureGroovyScript script, @CheckForNull Map<String, Object> params)
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
      List<LockableResource> resources, long queueItemId, String queueProjectName) {
    for (LockableResource r : resources)
      if (r.isReserved() || r.isQueued(queueItemId) || r.isLocked()) return false;
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

    boolean candidatesByScript = false;
    List<LockableResource> candidates;
    final SecureGroovyScript systemGroovyScript = requiredResources.getResourceMatchScript();
    if (requiredResources.label != null
        && requiredResources.label.isEmpty()
        && systemGroovyScript == null) {
      candidates = requiredResources.required;
    } else if (systemGroovyScript == null) {
      candidates = getResourcesWithLabel(requiredResources.label, params);
    } else {
      candidates = getResourcesMatchingScript(systemGroovyScript, params);
      candidatesByScript = true;
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
        // hence we use their unique resource names
        List<String> resourceNames = new ArrayList<>();
        for (LockableResource resource : resources) {
          resourceNames.add(resource.getName());
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
          }
        }
      }
    }
  }

  public synchronized void unlock(
      List<LockableResource> resourcesToUnLock, @Nullable Run<?, ?> build) {
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
    if (resourceNamesToUnLock == null || (resourceNamesToUnLock.isEmpty())) {
      return;
    }

    // process as many contexts as possible
    List<String> remainingResourceNamesToUnLock = new ArrayList<>(resourceNamesToUnLock);

    QueuedContextStruct nextContext = null;
    while (!remainingResourceNamesToUnLock.isEmpty()) {
      // check if there are resources which can be unlocked (and shall not be unlocked)
      Set<LockableResource> requiredResourceForNextContext = null;
      nextContext =
          this.getNextQueuedContext(remainingResourceNamesToUnLock, inversePrecedence, nextContext);

      /*
       * If nextContent == null and remainingResourceNamesToUnlock is not empty,
       * it means there are no ctx that can be started immediately after freeing remainingResourceNamesToUnlock.
       *
       * However, this does not mean that any _ephemeral_ resources can be freely deleted; there might
       * be contexts that get their needed resources freed only when any of the next runs release their resources
       * Example: Assume ctxA, ctxB and ctxC start in succession and the following events occur:
       * (1) ctxA: creates label X with 8 resources and immediately locks 5 of them: X(5/8). ctxA starts to run.
       * (2) ctxB: Starts and needs two more resources of X: the state of X changes to X(7/8).  ctxB starts to run.
       * (3) ctxC wants 7 resources, but cannot start, because only 1/8 if available -> ctxC cannot start yet
       * (4) ctxA finishes, the state of X changes to X(2/8). However, this is still not enough to start ctxC.
       * If we use the classic implementation of freeResources now, and X contains ephemeral resources,
       * the 5 resources of X formerly used by ctxA will be deleted. We get X(2/3), and ctxC will never start.
       * To prevent this, we need to split freeResources into a "real" freeResources and one deleteUnusedEphemeralResources.
       */
      if (nextContext == null) {
        this.freeResources(remainingResourceNamesToUnLock, build);
        deleteUnusedEphemeralResourcesAndLabels(remainingResourceNamesToUnLock);
        save();
        return;
      }

      requiredResourceForNextContext =
          checkResourcesAvailability(
              nextContext.getResources(), null, remainingResourceNamesToUnLock);

      // resourceNamesToUnlock contains the names of the previous resources.
      // requiredResourceForNextContext contains the resource objects which are required for the
      // next context.
      // It is guaranteed that there is an overlap between the two - the resources which are to be
      // reused.
      boolean needToWait = false;
      for (LockableResource requiredResource : requiredResourceForNextContext) {
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

        List<String> resourceNamesToLock = new ArrayList<>();

        // lock all (old and new resources)
        for (LockableResource requiredResource : requiredResourceForNextContext) {
          try {
            requiredResource.setBuild(nextContext.getContext().get(Run.class));
            resourceNamesToLock.add(requiredResource.getName());
          } catch (Exception e) {
            // skip this context, as the build cannot be retrieved (maybe it was deleted while
            // running?)
            LOGGER.log(
                Level.WARNING,
                "Skipping queued context for lock. Can not get the Run object from the context to proceed with lock, "
                    + "this could be a legitimate status if the build waiting for the lock was deleted or"
                    + " hard killed. More information at Level.FINE if debug is needed.");
            LOGGER.log(
                Level.FINE, "Can not get the Run object from the context to proceed with lock", e);
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
            resourceNamesToLock,
            nextContext.getContext(),
            nextContext.getResourceDescription(),
            nextContext.getVariableName(),
            inversePrecedence);
      }
    }
    save();
  }

  /** Determines which resources and/or labels are not in use freeing the resources
   * in remainingResourceNamesToUnLock and deletes them if they are ephemeral.
    * @param freedResourceNames names of the resources that have just been freed and are now candidates for deletion
   */
  private synchronized void deleteUnusedEphemeralResourcesAndLabels(List<String> freedResourceNames) {
    Set<String> labelsUsedByQueueContexts = new HashSet<>();
    Set<String> resourcesUsedByQueueContexts = new HashSet<>();
    final Set<String> labelDeletionCandidates = new HashSet<>();
    final Set<String> resourceDeletionCandidates = new HashSet<>(freedResourceNames);

    /*
     * We need to build two pairs of lists: Labels & resources used by the remaining, active queuedContext on the one hand,
     * and the labels & resources who are candidates for deletion.
     */
    queuedContexts.forEach(
      qc -> qc.getResources().forEach(
        rs -> {
          if (rs.label != null) {
            labelsUsedByQueueContexts.add(rs.label);
            resources.stream().filter(r -> r.getLabelsAsList().contains(rs.label)).forEach(
              r2 -> {
                resourcesUsedByQueueContexts.add(r2.getName());
              }
            );
          }

          rs.required.stream().forEach(
            r -> {
              labelsUsedByQueueContexts.addAll(r.getLabelsAsList());
              resourcesUsedByQueueContexts.add(r.getName());
            }
          );
        }
      )
    );

    resources.stream()
      .filter(r -> freedResourceNames.contains(r.getName()))
      .forEach(
        r -> {
          labelDeletionCandidates.addAll(
            r.getLabelsAsList().stream().filter( l -> l.length()>0 ).collect(Collectors.toSet())
          );
        });

    // Subtract the "in use" sets from the candidate sets
    labelDeletionCandidates.removeAll(labelsUsedByQueueContexts);
    resourceDeletionCandidates.removeAll(resourcesUsedByQueueContexts);

    // Now we know the items we can safely delete.
    resources.removeIf(r -> r.isEphemeral() && resourceDeletionCandidates.contains(r.getName()));
    resources.removeIf(r -> r.isEphemeral() && labelDeletionCandidates.contains(r.getLabels()));
  }

  /**
   * Returns the next queued context with all its requirements satisfied.
   *
   * @param resourceNamesToUnLock resource names locked at the moment but available is required (as
   *     they are going to be unlocked soon
   * @param inversePrecedence false pick up context as they are in the queue or true to take the
   *     most recent one (satisfying requirements)
   * @return the context or null
   */
  @CheckForNull
  private QueuedContextStruct getNextQueuedContext(
      List<String> resourceNamesToUnLock, boolean inversePrecedence, QueuedContextStruct from) {
    QueuedContextStruct newestEntry = null;
    int fromIndex = from != null ? this.queuedContexts.indexOf(from) + 1 : 0;
    if (!inversePrecedence) {
      for (int i = fromIndex; i < this.queuedContexts.size(); i++) {
        QueuedContextStruct entry = this.queuedContexts.get(i);
        if (checkResourcesAvailability(entry.getResources(), null, resourceNamesToUnLock) != null) {
          return entry;
        }
      }
    } else {
      long newest = 0;
      List<QueuedContextStruct> orphan = new ArrayList<>();
      for (int i = fromIndex; i < this.queuedContexts.size(); i++) {
        QueuedContextStruct entry = this.queuedContexts.get(i);
        if (checkResourcesAvailability(entry.getResources(), null, resourceNamesToUnLock) != null) {
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

  /** Creates the resource */
  public boolean createResource(String name) {
    return createResource(name, false);
  }

    /** Creates the resource */
  public synchronized boolean createResource(String name, boolean autoCreateEphemeral) {
    if (name != null) {
      LockableResource existent = fromName(name);
      if (existent == null) {
        LockableResource resource = new LockableResource(name);
        resource.setEphemeral(autoCreateEphemeral);
        getResources().add(resource);
        save();
        return true;
      }
    }
    return false;
  }

  /**
   * Creates a permanent resource and assigns a label to the resource.
   * @param name the name of the resource
   * @param label the label to be assigned to the new resource
   * @return true if the resource was created, false if it does not need to be created because it already exists
   * note that in the case of already existing resources, no check is performed if the label of the existing resource
   * matches the desired label.
   */
  public boolean createResourceWithLabel(String name, String label) {
    return createResourceWithLabel(name, label, false);
  }

  /**
   * Creates a resource and assigns a label to the resource.
   * @param name the name of the resource
   * @param label the label to be assigned to the new resource
   * @param ephemeral true if the resource should be deleted when the last reference to it disappears
   * @return true if the resource was created, false if it does not need to be created because it already exists
   * note that in the case of already existing resources, no check is performed if the label of the existing resource
   * matches the desired label.
   */
  public synchronized boolean createResourceWithLabel(String name, String label, boolean ephemeral) {
    if (name != null && label != null) {
      LockableResource existent = fromName(name);
      if (existent == null) {
        LockableResource resource = new LockableResource(name);
        resource.setEphemeral(ephemeral);
        resource.setLabels(label);
        getResources().add(resource);
        save();
        return true;
      }
    }
    return false;
  }

  /* Create an ephemeral label with a specified amount of ephemeral resources  */
  public synchronized boolean createEphemeralLabel(String label, int createLabelWithQuantity) throws InternalException {
    /* We create a new label if and only if:
       1. There is no resource with the specified label yet
       2. The label name is neither null nor the empty string
       3. createLabelWithQuantity is at least 1.
     */
    if (label != null && label.length() > 0 && createLabelWithQuantity > 0 && (! isValidLabel(label))) {
        for (int i=1; i<=createLabelWithQuantity; i++) {
          String resourceName = label + "-" + UUID.randomUUID().toString();
          if ( ! createResourceWithLabel(resourceName, label, true)) {
            throw new InternalException(
              String.format("Creation of ephemeral resource '%1$s' for new ephemeral label '%2$s' has failed.",
                resourceName, label));
          }  else {
            LOGGER.log(Level.FINE,
              String.format("Successfully created ephemeral resource '%1$s' for new ephemeral label '%2$s'", resourceName, label));
          }
        }
        // At this point, all resources (and, by implication, the new label) have been created successfully.
        return true;
    }
    // Fall-through if any pre-conditions are not fulfilled.
    return false;
  }

  public synchronized boolean reserve(List<LockableResource> resources, String userName) {
    for (LockableResource r : resources) {
      if (r.isReserved() || r.isLocked() || r.isQueued()) {
        return false;
      }
    }
    for (LockableResource r : resources) {
      r.setReservedBy(userName);
    }
    save();
    return true;
  }

  private void unreserveResources(@Nonnull List<LockableResource> resources) {
    for (LockableResource l : resources) {
      l.unReserve();
    }
    save();
  }

  public synchronized void unreserve(List<LockableResource> resources) {
    // make sure there is a list of resources to unreserve
    if (resources == null || (resources.isEmpty())) {
      return;
    }
    List<String> resourceNamesToUnreserve = new ArrayList<>();
    for (LockableResource r : resources) {
      resourceNamesToUnreserve.add(r.getName());
    }

    // check if there are resources which can be unlocked (and shall not be unlocked)
    Set<LockableResource> requiredResourceForNextContext = null;
    QueuedContextStruct nextContext =
        this.getNextQueuedContext(resourceNamesToUnreserve, false, null);

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
    requiredResourceForNextContext =
        checkResourcesAvailability(
            nextContext.getResources(), nextContextLogger, resourceNamesToUnreserve);
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
      List<String> resourceNamesToLock = new ArrayList<>();

      // lock all (old and new resources)
      for (LockableResource requiredResource : requiredResourceForNextContext) {
        try {
          requiredResource.setBuild(nextContext.getContext().get(Run.class));
          resourceNamesToLock.add(requiredResource.getName());
        } catch (Exception e) {
          // skip this context, as the build cannot be retrieved (maybe it was deleted while
          // running?)
          LOGGER.log(
              Level.WARNING,
              "Skipping queued context for lock. Can not get the Run object from the context to proceed with lock, "
                  + "this could be a legitimate status if the build waiting for the lock was deleted or"
                  + " hard killed. More information at Level.FINE if debug is needed.");
          LOGGER.log(
              Level.FINE, "Can not get the Run object from the context to proceed with lock", e);
          return;
        }
      }

      // continue with next context
      LockStepExecution.proceed(
          resourceNamesToLock,
          nextContext.getContext(),
          nextContext.getResourceDescription(),
          nextContext.getVariableName(),
          false);
    }
    save();
  }

  @Override
  public String getDisplayName() {
    return "External Resources";
  }

  public synchronized void reset(List<LockableResource> resources) {
    for (LockableResource r : resources) {
      r.reset();
    }
    save();
  }

  @Override
  public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
    try (BulkChange bc = new BulkChange(this)) {
      // reset resources to default which are not currently locked
      this.resources.removeIf(resource -> !resource.isLocked());
      req.bindJSON(this, json);
      bc.commit();
    } catch (IOException exception) {
      LOGGER.log(
          Level.WARNING, "Exception occurred while committing bulk change operation.", exception);
      return false;
    }
    return true;
  }

  /** @see #checkResourcesAvailability(List, PrintStream, List, boolean) */
  public synchronized Set<LockableResource> checkResourcesAvailability(
      List<LockableResourcesStruct> requiredResourcesList,
      @Nullable PrintStream logger,
      @Nullable List<String> lockedResourcesAboutToBeUnlocked) {
    boolean skipIfLocked = false;
    return this.checkResourcesAvailability(
        requiredResourcesList, logger, lockedResourcesAboutToBeUnlocked, skipIfLocked);
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

    for (LockableResourcesCandidatesStruct requiredResources : requiredResourcesCandidatesList) {
      // start with an empty set of selected resources
      List<LockableResource> selected = new ArrayList<>();

      // some resources might be already locked, but will be freed.
      // Determine if these resources can be reused
      if (lockedResourcesAboutToBeUnlocked != null) {
        for (LockableResource candidate : requiredResources.candidates) {
          if (selected.size() >= requiredResources.requiredAmount) {
            break;
          }
          if (lockedResourcesAboutToBeUnlocked.contains(candidate.getName())) {
            selected.add(candidate);
          }
        }
      }

      totalSelected += selected.size();
      requiredResources.selected = selected;
    }

    // if none of the currently locked resources can be reused,
    // this context is not suitable to be continued with
    if (lockedResourcesAboutToBeUnlocked != null && totalSelected == 0) {
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
