/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.BulkChange;
import hudson.model.*;

import java.io.IOException;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.model.Queue;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkins.plugins.lockableresources.queue.QueuedFreestyleStruct;
import org.jenkins.plugins.lockableresources.queue.QueuedStruct;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkins.plugins.lockableresources.queue.QueuedContextStruct;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.stapler.StaplerRequest;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nonnull;

@Extension
public class LockableResourcesManager extends GlobalConfiguration {

	@Deprecated
	private transient int defaultPriority;
	@Deprecated
	private transient String priorityParameterName;

	// Whenever a public method is called will read or alter the state of the resources, the resource states themselves
	// or the lock queue, you MUST take a read or write lock as appropriate.
    // And if you take a write lock, you probably want to call save() when you're done
	// Remember, read locks can't be escalated to write locks. Take a write lock if you might want to write.
	private transient ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock(true);
	private List<LockableResource> resources;
	private List<QueuedStruct> queuedContexts = new ArrayList<>();

	public LockableResourcesManager() {
		resources = new ArrayList<LockableResource>();
		load();
	}

	public List<LockableResource> getResources() {
		Lock accessLock = stateLock.readLock();
		accessLock.lock();
		try {
			return resources;
		} finally {
			accessLock.unlock();
		}
	}

	public List<LockableResource> getResourcesFromProject(String fullName) {
		Lock accessLock = stateLock.readLock();
		accessLock.lock();
		try {
			List<LockableResource> matching = new ArrayList<LockableResource>();
			for (LockableResource r : resources) {
				String rName = r.getQueueItemProject();
				if (rName != null && rName.equals(fullName)) {
					matching.add(r);
				}
			}
			return matching;
		} finally {
			accessLock.unlock();
		}
	}

	public List<LockableResource> getResourcesFromBuild(Run<?, ?> build) {
		Lock accessLock = stateLock.readLock();
		accessLock.lock();
		try {
			List<LockableResource> matching = new ArrayList<LockableResource>();
			for (LockableResource r : resources) {
				Run<?, ?> rBuild = r.getBuild();
				if (rBuild != null && rBuild == build) {
					matching.add(r);
				}
			}
			return matching;
		} finally {
			accessLock.unlock();
		}
	}

	public Boolean isValidLabel(String label)
	{
		Lock accessLock = stateLock.readLock();
		accessLock.lock();
		try {
			return this.getAllLabels().contains(label);
		} finally {
			accessLock.unlock();
		}
	}

	public Set<String> getAllLabels()
	{
		Lock accessLock = stateLock.readLock();
		accessLock.lock();
		try {
			Set<String> labels = new HashSet<String>();
			for (LockableResource r : this.resources) {
				String rl = r.getLabels();
				if (rl == null || "".equals(rl))
					continue;
				labels.addAll(Arrays.asList(rl.split("\\s+")));
			}
			return labels;
		} finally {
			accessLock.unlock();
		}
	}

	public int getFreeResourceAmount(String label)
	{
		Lock accessLock = stateLock.readLock();
		accessLock.lock();
		try {
			int free = 0;
			for (LockableResource r : this.resources) {
				if (r.isAvailable() && Arrays.asList(r.getLabels().split("\\s+")).contains(label))
					free += 1;
			}
			return free;
		} finally {
			accessLock.unlock();
		}
	}

	public List<LockableResource> getResourcesWithLabel(String label) {
		Lock accessLock = stateLock.readLock();
		accessLock.lock();
		try {
			List<LockableResource> found = new ArrayList<LockableResource>();
			for (LockableResource r : this.resources) {
				if (r.isValidLabel(label))
					found.add(r);
			}
			return found;
		} finally {
			accessLock.unlock();
		}
	}

	/**
	 * Get a list of available resources matching the script.
	 * @param script Script
	 * @param params Additional parameters
	 * @return List of the matching resources
	 * @throws ExecutionException Script execution failed for one of the resources.
	 *                            It is considered as a fatal failure since the requirement list may be incomplete
	 * @since TODO
	 */
	@Nonnull
	private List<LockableResource> getResourcesMatchingScript(@Nonnull SecureGroovyScript script,
															 @CheckForNull Map<String, Object> params) throws ExecutionException {
		List<LockableResource> found = new ArrayList<>();
		for (LockableResource r : this.resources) {
			if (r.isAvailable() && r.scriptMatches(script, params))
				found.add(r);
		}
		return found;
	}

	public LockableResource fromName(String resourceName) {
		Lock accessLock = stateLock.readLock();
		accessLock.lock();
		try {
			if (resourceName != null) {
				for (LockableResource r : resources) {
					if (resourceName.equals(r.getName()))
						return r;
				}
			}
			return null;
		} finally {
			accessLock.unlock();
		}
	}

	/**
	 * Try to acquire the resources required by the freestyleproject.
	 * @return List of the locked resources if the task has been accepted.
	 *         {@code null} if the item is still waiting for the resources
	 * @throws ExecutionException Cannot queue the resource due to the execution failure. Carries info in the cause
	 */
	@CheckForNull
	public List<LockableResource> freeStyleLockOrQueue(LockableResourcesStruct requiredResources,
													   long queueId, Job queueItemProject,
													   Map<String, Object> params, Logger log) throws ExecutionException {
		Lock accessLock = stateLock.writeLock();
		accessLock.lock();
		try {
			// Freestyle projects keep polling and so if already in queue, don't add again.
			for (QueuedStruct queuedContext : this.queuedContexts) {
				if (queuedContext instanceof QueuedFreestyleStruct) {
					long contextId = (Long) queuedContext.getIdentifier();
					if (contextId == queueId) {
						return null;
					}
				}
			}

			// Check to see any resources are already queued for this job. The build might not have been assigned
			// to them yet. This is specific to freestyle jobs that get the resources assigned to them before they start
			List<LockableResource> alreadyQueued = new ArrayList<>();
			for (LockableResource candidate : this.resources) {
				if (candidate.isQueued() &&
						candidate.getQueueItemId() == queueId &&
						candidate.getQueueItemProject().equals(queueItemProject.getFullName())) {
					alreadyQueued.add(candidate);
				}
			}
			if (!alreadyQueued.isEmpty()) {
				return alreadyQueued;
			}

			log.log(Level.INFO, "Project: {0} not already in queue, seeing if enough free resources {1}",
					new Object[]{queueItemProject.getFullName(), requiredResources.toLogString()});

			List<LockableResource> candidates = getCandidateResources(requiredResources, null, null);
			if (candidates == null || candidates.isEmpty()) {
				log.log(Level.INFO, "{0} found insufficient resource(s) to start. Queueing for correct amount: {1}",
						new Object[]{queueItemProject.getFullName(), requiredResources.toLogString()});
				//TODO Sue get the lock priority
				queueContext(new QueuedFreestyleStruct(queueId, queueItemProject, requiredResources,
						requiredResources.toLogString(), requiredResources.requiredVar, 0));
                save();
                return null;
			} else {
                List<LockableResource> queuedResources = setResourcesAsQueued(candidates, queueId, queueItemProject.getFullName());
			    save();
				return queuedResources;
			}
		} finally {
			accessLock.unlock();
		}
	}

	// This is for freestyle builds to mark resources as queued for them
	private List<LockableResource> setResourcesAsQueued(List<LockableResource> resources,
														long queueId,
														String projectName) {
		LOGGER.log(Level.INFO, "Queueing selected resources " + resources + " for build " + projectName);
		for (LockableResource rsc : resources) {
			rsc.setQueued(queueId, projectName);
		}
		return resources;
	}

	private List<LockableResource> selectResourcesFromCandidates(List<LockableResource> candidates,
																 int numberRequired) {
		// select randomly from candidates list for any resources that are free to be locked, until the number
		// required have been selected
		// if the numberRequired is 0, that means try to select all candidates
		List<LockableResource> selected =  new ArrayList<LockableResource>();
		Random rand = new Random();
		int candidatesSize = candidates.size();
		// Keep a record of which candidates seen
		HashSet<Integer> inspectedIndexes = new HashSet<>();
		while (inspectedIndexes.size() < candidatesSize) {
			int index = rand.nextInt(candidatesSize);
			// only check if this candidate is valid, if we haven't checked it already
			if (!inspectedIndexes.contains(index)) {
				LockableResource candidate = candidates.get(index);
				if (candidate.isAvailable())
					selected.add(candidate);
				if (numberRequired != 0 && (selected.size() >= numberRequired))
					break;
				inspectedIndexes.add(index);
			}
		}
		return selected;
	}

	/**
	 * For workflow jobs, try to lock the resources and queue if unavailable and return true if locked.
	 */
	public boolean lockOrQueue(LockableResourcesStruct resources,
											@Nullable StepContext context,
											LockStep lockStep,
											@Nullable PrintStream logger) {
		Lock accessLock = stateLock.writeLock();
		accessLock.lock();
		try {
			List<LockableResource> availableResources = getCandidateResources(resources, null, logger);
			if (availableResources != null && !availableResources.isEmpty()) {
				lockResourcesAndTriggerWorkflow(availableResources, context,
						lockStep.toString(), lockStep.variable);
			} else {
				if (logger != null) {
					logger.println("[" + lockStep + "] is locked, waiting...");
				}
				int priority = lockStep.lockPriority;
				if (lockStep.inversePrecedence) {
					// semantics of inversePrecedence (LIFO) are a little odd when there are also
					// priorities for other queued items
					if (priority == 0 && !this.queuedContexts.isEmpty()) {
						// if priority of 0 set to the current max of the queue + 1
						priority = this.queuedContexts.get(0).getLockPriority() + 1;
					} else {
						// if there is a priority, set it to 1 above that priority group
						priority += 1;
					}
				}
				queueContext(new QueuedContextStruct(context, resources,
						lockStep.toString(), lockStep.variable,
						priority));
			}
			save();
			return availableResources != null;
		} catch (ExecutionException e) {
			if (logger != null) {
				// This should never happen. Execution exceptions come from running scripts
				logger.println("Trying to log resources failed " + lockStep + " " + e);
			}
			return false;
		} finally {
			accessLock.unlock();
		}
	}

	/**
	 * Locks the resources for a workflow job and triggers it to proceed
 	 */

	private void lockResourcesAndTriggerWorkflow(List<LockableResource> resources,
																							 StepContext context,
																							 String resourceDescription,
																							 String resourceVariableName) {
		// since LockableResource contains transient variables, they cannot be correctly serialized
		// hence we use their unique resource names
		List<String> resourceNames = new ArrayList<>();
		try {
			for (LockableResource resource : resources) {
				resource.unqueue(); // this should be unnecessary. Workflow resources are never queued first.
				resourceNames.add(resource.getName());
				resource.setBuild(context.get(Run.class));
			}
			LockStepExecution.proceed(resourceNames, context, resourceDescription, resourceVariableName);
		} catch (Exception e) {
			// skip this context, as the build cannot be retrieved (maybe it was deleted while running?)
			LOGGER.log(Level.WARNING, "Skipping queued context for lock. Can not get the Run object from the context to proceed with lock, " +
					"this could be a legitimate status if the build waiting for the lock was deleted or" +
					" hard killed. More information at Level.FINE if debug is needed.");
			LOGGER.log(Level.WARNING, "Can not get the Run object from the context to proceed with lock", e);
			for (LockableResource r : resources) {
				r.setBuild(null);
			}
		}
	}

	/**
	 * Only called with FreestyleProjects
	 * A freestylebuild queues the resources on a project. This is what locks the resources with the
	 * actual build. This should never fail. The resources should already be queue for this project.
	 */
	public boolean lockQueuedResourcesWithBuild(List<LockableResource> resources, Run<?, ?> build) {
		// if resources is empty, then they have been unqueued as this build has been too slow starting and
		// has the queueing has timed out. This shouldn't happen, but has been seen on jenkins restarts.
		if (resources.isEmpty()) {
			return false;
		}
		Lock accessLock = stateLock.writeLock();
		accessLock.lock();
		try {
			LOGGER.log(Level.INFO, "Locking queued resources " + resources + " for freestyle build " + build.getFullDisplayName());
			for (LockableResource r : resources) {
				// check that we are actually unqueueing resources that were queued for this build
				if (r.getQueueItemProject().equals(build.getParent().getFullName())) {
					r.unqueue();
					r.setBuild(build);
				} else {
					return false;
				}
			}
			save();
			return true;
		} finally {
			accessLock.unlock();
		}
	}
	
	public void releaseResources(List<LockableResource> lockableResources, @Nullable Run<?, ?> build) {
		Lock accessLock = stateLock.writeLock();
		accessLock.lock();
		try {
			for (LockableResource resource : lockableResources) {
				// check we have the right build before releasing. Should be unnecessary
				if (build == null || (resource.getBuild() != null && build.getExternalizableId().equals(resource.getBuild().getExternalizableId()))) {
					resource.setBuild(null);
				}
			}
			//go through queue and proceed with all jobs that can be run as a result of releasing resources
			processQueue();
			save();
		} finally {
			accessLock.unlock();
		}
	}

	public void releaseResourceNames(List<String> resourceNames, @Nullable Run<?, ?> build) {
		List<LockableResource> resourcesToRelease = new ArrayList<>();
		for (String resourceName: resourceNames) {
			resourcesToRelease.add(fromName(resourceName));
		}
		// access lock acquired in this call
		releaseResources(resourcesToRelease, build);
	}

	private void processQueue() {
		for (Iterator<QueuedStruct> iter = this.queuedContexts.listIterator(); iter.hasNext();) {
			QueuedStruct queuedContext = iter.next();
			try {
				// If this queuedContext doesn't correspond to a valid build, remove it from queue
				List<LockableResource> availableResources = getCandidateResources(queuedContext.getResources(), null, null);
				// unqueue id the build for this queueitem has gone away or if there are enough resources for it
				if (!queuedContext.isBuildStatusGood() ||
						(availableResources != null && !availableResources.isEmpty())) {
					iter.remove();
					if (queuedContext instanceof QueuedContextStruct) {
						QueuedContextStruct queueItem = (QueuedContextStruct) queuedContext;
						lockResourcesAndTriggerWorkflow(availableResources, queueItem.getContext(),
								queuedContext.getResourceDescription(), queuedContext.getResourceVariableName());
					} else if (queuedContext instanceof QueuedFreestyleStruct) {
						QueuedFreestyleStruct freestyleStruct = (QueuedFreestyleStruct) queuedContext;
						// queue the resources for the project. The project is polling and will notice and start
						setResourcesAsQueued(availableResources,
								(Long) freestyleStruct.getIdentifier(),
								freestyleStruct.getBuildName());
					} else {
						LOGGER.log(Level.SEVERE, "Don't know how to assign resources for build " +
						queuedContext.getBuildName() + " removing from queue anyway");
					}
				}
			} catch (ExecutionException e) {
				//Don't unqueue as script processing getting candidates has failed
			}
		}
	}

	/**
	 * Creates the resource if it does not exist.
	 */
	public boolean createResource(String name) {
		Lock accessLock = stateLock.writeLock();
		accessLock.lock();
		try {
			LockableResource existent = fromName(name);
			if (existent == null) {
				this.resources.add(new LockableResource(name));
				save();
				processQueue();
				return true;
			}
            save();
            return false;
		} finally {
			accessLock.unlock();
		}
	}

	public boolean createResourceWithLabel(String name, String label) {
		Lock accessLock = stateLock.writeLock();
		accessLock.lock();
		try {
			LockableResource existent = fromName(name);
			if (existent == null) {
				this.resources.add(new LockableResource(name, "", label, null));
				LOGGER.log(Level.INFO, "Created " + name + " label " + label);
				save();
				processQueue();
				return true;
			}
            save();
            return false;
		} finally {
			accessLock.unlock();
		}
	}

	public boolean reserve(List<LockableResource> resources,
												 String userName) {
		Lock accessLock = stateLock.writeLock();
		accessLock.lock();
		try {
			for (LockableResource r : resources) {
				if (!r.isAvailable()) {
					return false;
				}
			}
			for (LockableResource r : resources) {
				r.setReservedBy(userName);
			}
			LOGGER.log(Level.INFO, "Reserved " + resources + " by " + userName);
			save();
			// no need to processQueue, nothing new is released
			return true;
		} finally {
			accessLock.unlock();
		}
	}

	public void unreserve(List<LockableResource> resources) {
		Lock accessLock = stateLock.writeLock();
		accessLock.lock();
		try {
			for (LockableResource r : resources) {
				r.unReserve();
			}
			LOGGER.log(Level.INFO, "Unreserved " + resources);
			save();
			processQueue();
		} finally {
			accessLock.unlock();
		}
	}

	public void reset(List<LockableResource> resources) {
		Lock accessLock = stateLock.writeLock();
		accessLock.lock();
		try {
			for (LockableResource r : resources) {
				r.reset();
			}
			LOGGER.log(Level.INFO, "Reset " + resources);
			save();
			processQueue();
		} finally {
			accessLock.unlock();
		}
	}

	@Override
	public String getDisplayName() {
		return "External Resources";
	}

	@Override
	public boolean configure(StaplerRequest req, JSONObject json)
			throws FormException {
		try {
			List<LockableResource> newResouces = req.bindJSONToList(
					LockableResource.class, json.get("resources"));
			for (LockableResource r : newResouces) {
				LockableResource old = fromName(r.getName());
				if (old != null) {
					r.setBuild(old.getBuild());
					r.setQueued(r.getQueueItemId(), r.getQueueItemProject());
				}
			}
			Lock accessLock = stateLock.writeLock();
			accessLock.lock();
			try {
				resources = newResouces;
				save();
				return true;
			} finally {
				accessLock.unlock();
			}
		} catch (JSONException e) {
			return false;
		}
	}

	/**
	 * Checks if there are enough resources available to satisfy the requirements specified
	 * within requiredResources and returns the necessary available resources.
	 * If not enough resources are available, returns empty collection.
	 */
	private List<LockableResource> getCandidateResources(LockableResourcesStruct requiredResources,
														 //TODO Sue test buildParams
														 @Nullable Map<String, Object> params,
														 @Nullable PrintStream logger) throws ExecutionException {
		// get possible resources
		int requiredAmount = requiredResources.getResourceCount();
		List<LockableResource> candidates = new ArrayList<>();
		final SecureGroovyScript systemGroovyScript = requiredResources.getResourceMatchScript();
		// groovy script specific to freestyle jobs
		if (systemGroovyScript != null) {
			candidates = getResourcesMatchingScript(systemGroovyScript, params);
		} else if (requiredResources.label != null && requiredResources.label.isEmpty()) {
			for (LockableResource resource: requiredResources.required) {
				// The way the resources are sometimes serialised, means that there might be a copy
				// of the LockableResource, with old locked status, so make sure to use up to date
				// information, by getting resource by name
				LockableResource freshResource = LockableResourcesManager.get().fromName(resource.getName());
				candidates.add(freshResource);
			}
		} else { // label is specified
			for (LockableResource resource : this.resources) {
				if (resource.isValidLabel(requiredResources.label))
					candidates.add(resource);
			}
		}

		// The candidates can't meet the criteria
		if (candidates.isEmpty() || candidates.size() < requiredAmount) {
			return new ArrayList<>();
		}

		if (requiredAmount == 0) { // 0 means all
			requiredAmount = candidates.size();
		}
		List<LockableResource> selected = selectResourcesFromCandidates(candidates, requiredAmount);
		if (selected.size() < requiredAmount) {
			if (logger != null) {
				logger.println("Found " + selected.size() + " available resource(s). Waiting for correct amount: " + requiredAmount + ".");
			}
			return new ArrayList<>();
		}

		return selected;
	}

	/*
	 * Adds the given context and the required resources to the queue if
	 * this context is not already queued.
	 */
	protected void queueContext(QueuedStruct queuedStruct) {
		Lock accessLock = stateLock.writeLock();
		accessLock.lock();
		try {
			int priorityQueueIndex = 0;
			// Scan through the whole queue to find if the context is a duplicate
			for (QueuedStruct entry : this.queuedContexts) {
				if (entry.getIdentifier() == queuedStruct.getIdentifier()) {
					return;
				}
				// Search for where the insertion point would be if a non zero priority were specified
				// Entries will be in decreasing priority order. Stop moving index forward when entry priority  is less than
				// the requested priority
				if (entry.getLockPriority() >= queuedStruct.getLockPriority()) {
					priorityQueueIndex++;
				}
			}

			if (queuedStruct.getLockPriority() == 0)
				// Priority of 0 is the lowest priority, so can safely add to the end of the queue
				this.queuedContexts.add(queuedStruct);
			else
				// add into the queue to maintain priority ordering
				this.queuedContexts.add(priorityQueueIndex, queuedStruct);
			LOGGER.log(Level.INFO, "Queued Contexts " + this.queuedContexts);
			save();
		} finally {
			accessLock.unlock();
		}
	}

	public boolean unqueueContext(StepContext context) {
		Lock accessLock = stateLock.writeLock();
		accessLock.lock();
		try {
			for (Iterator<QueuedStruct> iter = this.queuedContexts.listIterator(); iter.hasNext(); ) {
				QueuedStruct queuedStruct = iter.next();
				if (queuedStruct.getIdentifier() == context) {
					LOGGER.log(Level.INFO, "Unqueueing {} from the queuedContexts ",
							new Object[]{queuedStruct});
					iter.remove();
					LOGGER.log(Level.INFO, "Queued Contexts " + this.queuedContexts);
					save();
					return true;
				}
			}
			return false;
		} finally {
			accessLock.unlock();
		}
	}

	public static LockableResourcesManager get() {
		return (LockableResourcesManager) Jenkins.getInstance()
				.getDescriptorOrDie(LockableResourcesManager.class);
	}

	public int getQueuedContextsSize() {
		Lock accessLock = stateLock.readLock();
		accessLock.lock();
		try {
			return this.queuedContexts.size();
		} finally {
			accessLock.unlock();
		}
	}

	public ArrayList<QueuedStruct> getQueuedContexts() {
		Lock accessLock = stateLock.readLock();
		accessLock.lock();
		try {
			return new ArrayList<>(this.queuedContexts);
		} finally {
			accessLock.unlock();
		}
	}

	public void save() {
		Lock accessLock = stateLock.readLock();
		accessLock.lock();
		try {
			if (BulkChange.contains(this))
				return;

			try {
				getConfigFile().write(this);
			} catch (IOException e) {
				LOGGER.log(Level.WARNING, "Failed to save " + getConfigFile(), e);
			}
		} finally {
			accessLock.unlock();
		}
	}

	private static final Logger LOGGER = Logger.getLogger(LockableResourcesManager.class.getName());

}
