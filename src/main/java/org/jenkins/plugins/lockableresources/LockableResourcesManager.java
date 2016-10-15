/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import hudson.Extension;
import hudson.model.Run;

import java.io.PrintStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Collection;
import java.util.TreeSet;

import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkins.plugins.lockableresources.queue.QueuedContextStruct;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.stapler.StaplerRequest;

import edu.umd.cs.findbugs.annotations.Nullable;
import groovy.lang.Tuple2;
import java.util.Collections;
import org.codehaus.groovy.runtime.AbstractComparator;

@Extension
public class LockableResourcesManager extends GlobalConfiguration {
	private static final Logger LOGGER = Logger.getLogger(LockableResource.class.getName());

	@Deprecated
	private transient int defaultPriority;
	@Deprecated
	private transient String priorityParameterName;
	private List<LockableResource> resources;


	/**
	 * Only used when this lockable resource is tried to be locked by {@link LockStep},
	 * otherwise (freestyle builds) regular Jenkins queue is used.
	 */
	private List<QueuedContextStruct> queuedContexts = new ArrayList<QueuedContextStruct>();

	public LockableResourcesManager() {
		resources = new ArrayList<LockableResource>();
		load();
	}

	public List<LockableResource> getResources() {
		return resources;
	}

	public List<LockableResource> getResourcesFromProject(String fullName) {
		List<LockableResource> matching = new ArrayList<LockableResource>();
		for (LockableResource r : resources) {
			String rName = r.getQueueItemProject();
			if (rName != null && rName.equals(fullName)) {
				matching.add(r);
			}
		}
		return matching;
	}

	public List<LockableResource> getResourcesFromBuild(Run<?, ?> build) {
		List<LockableResource> matching = new ArrayList<LockableResource>();
		for (LockableResource r : resources) {
			Run<?, ?> rBuild = r.getBuild();
			if (rBuild != null && rBuild == build) {
				matching.add(r);
			}
		}
		return matching;
	}

	public Boolean isValidLabel(String label)
	{
		return label.startsWith(LockableResource.GROOVY_LABEL_MARKER)
				|| (label.startsWith("${") && label.endsWith("}"))
				|| this.getAllLabels().contains(label);
	}

	public TreeSet<String> getAllLabels()
	{
		TreeSet<String> labels = new TreeSet<>();
		for (LockableResource r : this.resources) {
			String rl = r.getLabels();
			if (rl == null || "".equals(rl))
				continue;
			labels.addAll(Arrays.asList(rl.split("\\s+")));
		}
		return labels;
	}
        
	public TreeSet<ResourceCapability> getAllCapabilities() {
		TreeSet<ResourceCapability> capabilities = new TreeSet<>();
		for (LockableResource r : this.resources) {
			capabilities.addAll(r.getCapabilities());
			capabilities.add(r.getMyselfAsCapability());
		}
		return capabilities;
	}

	public TreeSet<ResourceCapability> getCapabilities(Collection<ResourceCapability> neededCapabilities, Collection<ResourceCapability> prohibitedCapabilities) {
		TreeSet<ResourceCapability> capabilities = new TreeSet();
		for (LockableResource r : this.resources) {
			if(r.hasCapabilities(neededCapabilities, prohibitedCapabilities)) {
				capabilities.addAll(r.getCapabilities());
				capabilities.add(r.getMyselfAsCapability());
			}
		}
		return capabilities;
	}

	public int getFreeResourceAmount(Collection<ResourceCapability> neededCapabilities, Collection<ResourceCapability> prohibitedCapabilities)
	{
		int free = 0;
		for (LockableResource r : this.resources) {
			if (r.isLocked() || r.isQueued() || r.isReserved()) {
			} else if (r.hasCapabilities(neededCapabilities, prohibitedCapabilities)) {
				free += 1;
			}
		}
		return free;
	}
	
	public int getFreeResourceAmount(String labels)
	{
		int free = 0;
		Set<ResourceCapability> capabilities = ResourceCapability.splitCapabilities(labels);
		for (LockableResource r : this.resources) {
			if (r.isLocked() || r.isQueued() || r.isReserved()) {
			} else if (r.hasCapabilities(capabilities)) {
				free += 1;
			}
		}
		return free;
	}

	public List<LockableResource> getResourcesWithCapabilities(Collection<ResourceCapability> capabilities,
			Map<String, Object> params) {
		List<LockableResource> found = new ArrayList<>();
		for (LockableResource r : this.resources) {
			if (r.hasCapabilities(capabilities, null, params)) {
				found.add(r);
			}
		}
		return found;
	}

	public LockableResource fromName(String resourceName) {
		if (resourceName != null) {
			for (LockableResource r : resources) {
				if (resourceName.equals(r.getName()))
					return r;
			}
		}
		return null;
	}

	public synchronized boolean queue(List<LockableResource> resources,
			long queueItemId) {
		for (LockableResource r : resources)
			if (r.isReserved() || r.isQueued(queueItemId) || r.isLocked())
				return false;
		for (LockableResource r : resources)
			r.setQueued(queueItemId);
		return true;
	}

	public synchronized List<LockableResource> queue(LockableResourcesStruct requiredResources,
	                                                 long queueItemId,
	                                                 String queueItemProject,
	                                                 int number,  // 0 means all
	                                                 Map<String, Object> params,
	                                                 Logger log) {
		log.fine("Add job in queue, with these data: " + requiredResources.toString());
		
		List<LockableResource> finalSelected = new ArrayList<>();
		if (!checkCurrentResourcesStatus(finalSelected, queueItemProject, queueItemId, log)) {
			// The project has another buildable item waiting -> bail out
			log.log(Level.FINEST, "{0} has another build waiting resources." +
			        " Waiting for it to proceed first.",
			        new Object[]{queueItemProject});
			return null;
		}

		// Get all possible resources that are matching requirements (locked/unlocked)
		List<LockableResource> candidates = new ArrayList<>();
		if (requiredResources.label != null && requiredResources.label.isEmpty()) {
			log.fine("Add resources by name: " + requiredResources.required);
			candidates = requiredResources.required;
		} else {
			log.fine("Add resources by labels/capabilities: " + requiredResources.label);
			Collection<ResourceCapability> capabilities = ResourceCapability.splitCapabilities(requiredResources.label);
			candidates = getResourcesWithCapabilities(capabilities, params);
			log.fine("Possible resources:");
			for(LockableResource r: candidates) {
				log.fine("   - " + r.getName());
			}
		}

		// Extract resources that are free
		// Extract the best resources based on their capabilities
		List<Tuple2<LockableResource, Double>> unlocked = new ArrayList<>();
		for (LockableResource r : candidates) {
			if (!r.isReserved() && !r.isLocked() && !r.isQueued()) {
				double cost = 0;
				Set<ResourceCapability> capabilities = r.getCapabilities();
				int nFree = getFreeResourceAmount(capabilities, null);
				int nMax = getResourcesWithCapabilities(capabilities, null).size();
				cost = (nMax - nFree) * (resources.size() / nMax) + capabilities.size();
				unlocked.add(new Tuple2<>(r, cost));
			}
		}
		Collections.sort(unlocked, new AbstractComparator<Tuple2<LockableResource, Double>>() {
			@Override
			public int compare(Tuple2<LockableResource, Double> o1, Tuple2<LockableResource, Double> o2) {
				return o1.getSecond().compareTo(o2.getSecond());
			}
		});

		// if did not get wanted amount or did not get all
		int required_amount = (number == 0) ? candidates.size() : number;
		
		LOGGER.finer("Costs for using resources:");
		for (Tuple2<LockableResource, Double> t : unlocked) {
			LockableResource r = t.getFirst();
			String logSuffix = "";
			if(finalSelected.size() < required_amount) {
				if(finalSelected.contains(r)) {
					logSuffix = " (already selected)";
				} else {
					logSuffix = " (selected)";
					finalSelected.add(r);
				}
			}
			LOGGER.info(" - " + r.getName() + ": " + t.getSecond() + logSuffix);
		}

		if (finalSelected.size() < required_amount) {
			log.log(Level.FINEST, "{0} found {1} resource(s) to queue." +
			        "Waiting for correct amount: {2}.",
			        new Object[]{queueItemProject, finalSelected.size(), required_amount});
			// just to be sure, clean up
			for (LockableResource x : resources) {
				if (x.getQueueItemProject() != null &&
				    x.getQueueItemProject().equals(queueItemProject))
					x.unqueue();
			}
			return null;
		}
		
		for (LockableResource rsc : finalSelected) {
			rsc.setQueued(queueItemId, queueItemProject);
		}
		return finalSelected;
	}

	// Adds already selected (in previous queue round) resources to 'selected'
	// Return false if another item queued for this project -> bail out
	private boolean checkCurrentResourcesStatus(List<LockableResource> selected,
	                                            String project,
	                                            long taskId,
	                                            Logger log) {
		for (LockableResource r : resources) {
			// This project might already have something in queue
			String rProject = r.getQueueItemProject();
			if (rProject != null && rProject.equals(project)) {
				if (r.isQueuedByTask(taskId)) {
					// this item has queued the resource earlier
					selected.add(r);
				} else {
					// The project has another buildable item waiting -> bail out
					log.log(Level.FINEST, "{0} has another build " +
						"that already queued resource {1}. Continue queueing.",
						new Object[]{project, r});
					return false;
				}
			}
		}
		return true;
	}
	
	public synchronized boolean lock(List<LockableResource> resources, Run<?, ?> build, @Nullable StepContext context) {
		return lock(resources, build, context, null, false);
	}

	/**
	 * Try to lock the resource and return true if locked.
	 */
	public synchronized boolean lock(List<LockableResource> resources,
			Run<?, ?> build, @Nullable StepContext context, @Nullable String logmessage, boolean inversePrecedence) {
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
				List<String> resourceNames = new ArrayList<String>();
				for (LockableResource resource : resources) {
					resourceNames.add(resource.getName());
				}
				LockStepExecution.proceed(resourceNames, context, logmessage, inversePrecedence);
			}
		}
		save();
		return !needToWait;
	}
	
	private synchronized void freeResources(List<String> unlockResourceNames, Run<?, ?> build) {
		for (String unlockResourceName : unlockResourceNames) {
			for (LockableResource resource : this.resources) {
				if (resource.getName().equals(unlockResourceName)) {
					if (resource == null || (resource.getBuild() != null && build.getExternalizableId().equals(resource.getBuild().getExternalizableId()))) {
						// No more contexts, unlock resource
						resource.unqueue();
						resource.setBuild(null);
					}
				}
			}
		}
	}

	public synchronized void unlock(List<LockableResource> resourcesToUnLock,
			Run<?, ?> build, @Nullable StepContext context) {
		unlock(resourcesToUnLock, build, context, null, false);
	}

	public synchronized void unlock(@Nullable List<LockableResource> resourcesToUnLock,
			Run<?, ?> build, @Nullable StepContext context,
			@Nullable List<String> resourceNamesToUnLock, boolean inversePrecedence) {
		// create list of names on the fly if necessary
		if (resourceNamesToUnLock == null) {
			resourceNamesToUnLock = new ArrayList<String>();
		}

		// add names from legacy list to new list of names
		if (resourcesToUnLock != null) {
			for (LockableResource r : resources) {
				resourceNamesToUnLock.add(r.getName());
			}
		}

		// check if there are resources which can be unlocked (and shall not be unlocked)
		List<LockableResource> requiredResourceForNextContext = null;
		QueuedContextStruct nextContext = this.getNextQueuedContext(resourceNamesToUnLock, inversePrecedence);

		// no context is queued which can be started once these resources are free'd.
		if (nextContext == null) {
			this.freeResources(resourceNamesToUnLock, build);
			save();
			return;
		}
			
		// remove context from queue and process it
		requiredResourceForNextContext = getAvailableResources(nextContext.getResources(), null, resourceNamesToUnLock);
		this.queuedContexts.remove(nextContext);
			
		// resourceNamesToUnlock contains the names of the previous resources.
		// requiredResourceForNextContext contains the resource objects which are required for the next context.
		// It is guaranteed that there is an overlap between the two - the resources which are to be reused.
		boolean needToWait = false;
		for (LockableResource requiredResource : requiredResourceForNextContext) {
			if (!resourceNamesToUnLock.contains(requiredResource.getName())) {
				if (requiredResource.isReserved() || requiredResource.isLocked()) {
					needToWait = true;
					break;
				}
			}
		}

		if (needToWait) {
			this.freeResources(resourceNamesToUnLock, build);
			save();
			return;
		} else {
			// lock all (old and new resources)
			for (LockableResource requiredResource : requiredResourceForNextContext) {
				try {
					requiredResource.setBuild(nextContext.getContext().get(Run.class));
				} catch (Exception e) {
					throw new IllegalStateException("Can not access the context of a running build", e);
				}
			}

			// determine old resources no longer needed
			List<String> freeResources = new ArrayList<String>();
			for (String resourceNameToUnlock : resourceNamesToUnLock) {
				boolean resourceStillNeeded = false;
				for (LockableResource requiredResource : requiredResourceForNextContext) {
					if (resourceNameToUnlock == requiredResource.getName()) {
						resourceStillNeeded = true;
						break;
					}
				}

				if (!resourceStillNeeded) {
					freeResources.add(resourceNameToUnlock);
				}
			}

			// free old resources no longer needed
			this.freeResources(freeResources, build);

			// continue with next context
			List<String> resourceNames = new ArrayList<String>();
			for (LockableResource resource : this.resources) {
				resourceNames.add(resource.getName());
			}
			LockStepExecution.proceed(resourceNames, nextContext.getContext(), nextContext.getResourceDescription(), inversePrecedence);
		}
		save();
	}

	private QueuedContextStruct getNextQueuedContext(List<String> resourceNamesToUnLock, boolean inversePrecedence) {
		if (this.queuedContexts == null) {
			return null;
		}

		QueuedContextStruct newestEntry = null;
		List<LockableResource> requiredResourceForNextContext = null;
		if (!inversePrecedence) {
			for (QueuedContextStruct entry : this.queuedContexts) {
				if (getAvailableResources(entry.getResources(), null, resourceNamesToUnLock) != null) {
					return entry;
				}
			}
		} else {
			long newest = 0;
			for (QueuedContextStruct entry : this.queuedContexts) {
				if (getAvailableResources(entry.getResources(), null, resourceNamesToUnLock) != null) {
					try {
						Run<?, ?> run = entry.getContext().get(Run.class);
						if (run.getStartTimeInMillis() > newest) {
							newest = run.getStartTimeInMillis();
							newestEntry = entry;
						}
					} catch (Exception e) {
						// skip this one
					}
				}
			}
		}

		return newestEntry;
	}

	/**
	 * Creates the resource if it does not exist.
	 */
	public synchronized boolean createResource(String name) {
		LockableResource existent = fromName(name);
		if (existent == null) {
			getResources().add(new LockableResource(name));
			save();
			return true;
		}
		return false;
	}

	public synchronized boolean createResourceWithLabel(String name, String label) {
		LockableResource existent = fromName(name);
		if (existent == null) {
			getResources().add(new LockableResource(name, "", label, null));
			save();
			return true;
		}
		return false;
	}

	public synchronized boolean reserve(List<LockableResource> resources,
			String userName) {
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

	public synchronized void unreserve(List<LockableResource> resources) {
		for (LockableResource r : resources) {
			r.unReserve();
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
			resources = newResouces;
			save();
			return true;
		} catch (JSONException e) {
			return false;
		}
	}

	/*
	 * checks if there are enough resources available to satifiy the requirements specified
	 * within requiredResources and returns the necessary available resources.
	 * If not enough resources are available, returns null.
	 */
	public synchronized List<LockableResource> getAvailableResources(LockableResourcesStruct requiredResources,
			@Nullable PrintStream logger, @Nullable List<String> alreadyLockedResources) {
		// get possible resources
		int required_amount = 0; // 0 means all
		List<LockableResource> candidates = new ArrayList<LockableResource>();
		if (requiredResources.label != null && requiredResources.label.isEmpty()) {
			candidates = requiredResources.required;
		} else {
			Set<ResourceCapability> capabilities = ResourceCapability.splitCapabilities(requiredResources.label);
			candidates = getResourcesWithCapabilities(capabilities, null);
			if (requiredResources.requiredNumber != null) {
				try {
					required_amount = Integer.parseInt(requiredResources.requiredNumber);
				} catch (NumberFormatException e) {
					required_amount = 0;
				}
			}
		}

		if (required_amount == 0) {
			required_amount = candidates.size();
		}

		// start with an empty set of selected resources
		List<LockableResource> selected = new ArrayList<LockableResource>();

		// some resources might be already locked, but will be freeed.
		// Determine if these resources can be reused
		if (alreadyLockedResources != null) {
			for (LockableResource candidate : candidates) {
				if (alreadyLockedResources.contains(candidate.getName())) {
					selected.add(candidate);
				}
			}
			// if none of the currently locked resources can be reussed,
			// this context is not suitable to be continued with
			if (selected.size() == 0) {
				return null;
			}
		}

		for (LockableResource rs : candidates) {
			if (selected.size() >= required_amount) {
				break;
			}
			if (!rs.isReserved() && !rs.isLocked()) {
				selected.add(rs);
			}
		}

		if (selected.size() != required_amount && logger != null) {
			logger.println("Found " + selected.size() + " available resource(s). Waiting for correct amount: " + required_amount + ".");
			return null;
		}

		return selected;
	}

    /*
	 * Adds the given context and the required resources to the queue if
	 * this context is not yet queued.
	 */
	public void queueContext(StepContext context, LockableResourcesStruct requiredResources, String resourceDescription) {
		for (QueuedContextStruct entry : this.queuedContexts) {
			if (entry.getContext() == context) {
				return;
			}
		}

		this.queuedContexts.add(new QueuedContextStruct(context, requiredResources, resourceDescription));
		save();
	}

	public boolean unqueueContext(StepContext context) {
		for (Iterator<QueuedContextStruct> iter = this.queuedContexts.listIterator(); iter.hasNext(); ) {
			if (iter.next().getContext() == context) {
				iter.remove();
				save();
				return true;
			}
		}
		return false;
	}

	public static LockableResourcesManager get() {
		return (LockableResourcesManager) Jenkins.getInstance()
				.getDescriptorOrDie(LockableResourcesManager.class);
	}

}
