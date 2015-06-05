/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013-2015, 6WIND S.A.                                 *
 *                          SAP SE                                     *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import hudson.Plugin;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.AbstractBuild;
import hudson.model.Queue;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.jenkins.plugins.lockableresources.Constants.*;
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;

import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

public class LockableResourcesManager extends Plugin {
	
	private static final transient Random rand = new Random();
	private static final Logger LOGGER = Logger.getLogger(LockableResourcesManager.class.getName());

	private final LinkedHashSet<String> loadBalancingLabels;
	private boolean useResourcesEvenly = false;
	private final LinkedHashSet<LockableResource> resources;

	private final transient Map<String,Set<LockableResource>> labelsCache = new TreeMap<String,Set<LockableResource>>();
	private final transient Map<String,Set<LockableResource>> lbLabelsCache = new HashMap<String,Set<LockableResource>>();
	private final transient Map<String,LockableResource> resourceMapCache = new HashMap<String,LockableResource>();

	public LockableResourcesManager() {
		super();
		resources = new LinkedHashSet<LockableResource>();
		loadBalancingLabels = new LinkedHashSet<String>();
		try {
			load();
		}
		catch ( IOException ex ) {
			LOGGER.log(Level.SEVERE, "Unable to load plugin configuration!", ex);
			throw new RuntimeException("Unable to load plugin configuration!", ex);
		}
	}

	public Collection<LockableResource> getResources() {
		return resources;
	}

	public String getLoadBalancingLabels() {
		if ( loadBalancingLabels.size() > 0 ) {
			StringBuilder sb = new StringBuilder();
			for ( String l : loadBalancingLabels ) {
				sb.append(" ").append(l);
			}
			return sb.substring(1);
		}
		else {
			return null;
		}
	}

	public boolean getUseResourcesEvenly() {
		return useResourcesEvenly;
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

	public List<LockableResource> getResourcesFromBuild(AbstractBuild<?, ?> build) {
		List<LockableResource> matching = new ArrayList<LockableResource>();
		for (LockableResource r : resources) {
			AbstractBuild<?, ?> rBuild = r.getBuild();
			if (rBuild != null && rBuild == build) {
				matching.add(r);
			}
		}
		return matching;
	}

	public boolean isValidLabel(String label)
	{
		if (label == null) return false;
		return this.labelsCache.containsKey(label);
	}

	public Set<String> getAllLabels()
	{
		return Collections.unmodifiableSet(labelsCache.keySet());
	}

	public int getFreeResourceAmount(String label)
	{
		int free = 0;
		for ( LockableResource r : labelsCache.get(Util.fixEmpty(label)) ) {
			if ( r.isFree() ) free++;
		}
		return free;
	}

	public List<LockableResource> getResourcesWithLabel(String label) {
		List<LockableResource> found = new ArrayList<LockableResource>();
		for (LockableResource r : this.resources) {
			if (r.isValidLabel(label))
				found.add(r);
		}
		return found;
	}

	public LockableResource fromName(String resourceName) {
		return resourceMapCache.get(resourceName);
	}

	public synchronized Collection<LockableResource> queue(LockableResourcesStruct requiredResources,
	                                                       Queue.Item queueItem,
	                                                       String queueItemProject,
	                                                       int numRequired ) { // 0 means all
		// using a TreeSet here to ensure consistant ordering in logging/messaging output
		Set<LockableResource> selected = new TreeSet<LockableResource>();

		// ensure there is a resources build action available to store state on
		LockedResourcesBuildAction action = queueItem.getAction(LockedResourcesBuildAction.class);
		if ( action == null ) {
			action = new LockedResourcesBuildAction();
			queueItem.addAction(action);
		}

		// check for any already queue resources
		checkCurrentResourcesStatus(selected, action.matchedResources, queueItem.id);

		ArrayList<LockableResource> candidates = new ArrayList<LockableResource>(requiredResources.required);
		LOGGER.log(Level.FINEST, "Candidates: {0}", candidates);

		numRequired = numRequired <= 0 ? candidates.size() : numRequired;
		int totalNumRequired = numRequired;

		// check that all currently selected resources are still candidates
		// also remove any already selected resources from the candidates list
		Iterator<LockableResource> it = selected.iterator();
		while ( it.hasNext() ) {
			LockableResource selectedResource = it.next();
			if ( numRequired > 0 && candidates.remove(selectedResource) ) {
				numRequired--;
			}
			else {
				it.remove();
				selectedResource.unqueue();
			}
		}

		if ( numRequired <= 0 ) {
			LOGGER.log(Level.FINE, "Required resources already queued: {0}", selected);
		}
		else {
			List<LockableResource> availableCandidates = new ArrayList<LockableResource>();
			for (LockableResource rs : candidates) {
				if (!rs.isReserved() && !rs.isLocked() && !rs.isQueued())
					availableCandidates.add(rs);
			}
			LOGGER.log(Level.FINEST, "Available candidates: {0}", availableCandidates);

			// only use fancy logic if we don't need to lock all of them
			if ( numRequired < candidates.size() ) {
				LOGGER.log(Level.FINEST, "Selecting {0} resources.", numRequired);
				if ( !loadBalancingLabels.isEmpty() ) {
					LOGGER.log(Level.FINEST, "Load balancing labels: {0}", loadBalancingLabels);
					// now filter based on the load balancing labels parameter
					// first break our available candidates into a list for each LB label
					Map<String,List<LockableResource>> groups = new HashMap<String,List<LockableResource>>(loadBalancingLabels.size() + 1);
					for (LockableResource r : availableCandidates) {
						boolean foundLabel = false;
						for ( String label : loadBalancingLabels ) {
							if ( r.isValidLabel(label) ) {
								foundLabel = true;
								if ( !groups.containsKey(label) ) groups.put(label, new ArrayList<LockableResource>());
								groups.get(label).add(r);
								break;
							}
						}
						if ( !foundLabel ) {
							if ( !groups.containsKey(null) ) groups.put(null, new ArrayList<LockableResource>());
							groups.get(null).add(r);
						}
					}
					LOGGER.log(Level.FINER, "Load Balancing Groups: {0}", groups);
					// now repeatedly select a candidate resource from the label with the lowest current usage
					boolean resourcesLeft = true;
					while ( selected.size() < totalNumRequired && resourcesLeft ) {
						resourcesLeft = false;
						double lowestUsage = 2;
						String lowestUsageLabel = null;
						for ( String label : groups.keySet() ) {
							if ( groups.get(label).size() > 0 ) {
							double usage = calculateLbLabelUsage(label);
								if ( usage < lowestUsage ) {
									resourcesLeft = true;
									lowestUsage = usage;
									lowestUsageLabel = label;
								}
							}
						}
						LOGGER.log(Level.FINEST, "Lowest usage label: {0}", lowestUsageLabel);
						if ( resourcesLeft ) {
							List<LockableResource> group = groups.get(lowestUsageLabel);
							LockableResource r = selectResourceToUse(group);
							group.remove(r);
							selected.add(r);
							r.setQueued(queueItem.id, queueItemProject);
							LOGGER.log(Level.FINER, "Queued resource lock on: {0}", r);
						}
					}
				}
				else {
					while ( selected.size() < totalNumRequired && availableCandidates.size() > 0 ) {
						LockableResource r = selectResourceToUse(availableCandidates);
						availableCandidates.remove(r);
						selected.add(r);
					}
				}
			}
			else {
				LOGGER.log(Level.FINER, "Selecting all available specified resources.");
				selected.addAll(availableCandidates);
			}

			LOGGER.log(Level.FINE, "Selected resources: {0}", selected);
		}

		// if did not get wanted amount or did not get all
		if (selected.size() != totalNumRequired) {
			LOGGER.log(Level.FINEST, "{0} found {1} resource(s) to queue. Waiting for correct amount: {2}.",
					new Object[]{queueItemProject, selected.size(), totalNumRequired});
			// just to be sure, clean up
			for (LockableResource r : selected) {
				    r.unqueue();
			}
			return null;
		}

		LOGGER.log(Level.FINER, "Queuing locks for selected resources: {0}", selected);
		action.matchedResources.clear();
		for (LockableResource rsc : selected) {
			rsc.setQueued(queueItem.id, queueItemProject);
			action.matchedResources.add(rsc.getName());
		}
		return selected;
	}

	private LockableResource selectResourceToUse( List<LockableResource> resources ) {
		if ( useResourcesEvenly ) {
			return resources.get(rand.nextInt(resources.size()));
		}
		//else
			return resources.get(0);
	}

	// Adds already selected (in previous queue round) resources to 'selected'
	// Return false if another item queued for this project -> bail out
	private void checkCurrentResourcesStatus(Collection<LockableResource> selected,
	                                         Collection<String> matchedResources,
	                                         int queueId) {
		for (String rName : matchedResources) {
			for (LockableResource r : resources) {
				if ( r.getName().equals(rName) ) {
					if ( r.isQueuedByTask(queueId) ) {
						selected.add(r);
					}
					break;
				}
			}
		}
	}

	public synchronized boolean lock(Collection<String> resourceNames, AbstractBuild<?, ?> build) {
		ArrayList<LockableResource> resourcesToLock = new ArrayList<LockableResource>(resourceNames.size());
		for (String rName : resourceNames) {
			LockableResource r = fromName(rName);
			if (r == null || r.isReserved() || r.isLocked()) {
				return false;
			}
			resourcesToLock.add(r);
		}
		for (LockableResource r : resourcesToLock) {
			r.unqueue();
			r.setBuild(build);
		}
		return true;
	}

	public synchronized void unlock(List<LockableResource> resources,
			AbstractBuild<?, ?> build) {
		for (LockableResource r : resources) {
			if (build == null || build == r.getBuild()) {
				r.unqueue();
				r.setBuild(null);
			}
		}
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

	public synchronized void reset(List<LockableResource> resources) {
		for (LockableResource r : resources) {
			r.reset();
		}
		save();
	}

	@Override
	public synchronized void configure(StaplerRequest req, JSONObject json) {
		String loadBalancingLabelsString = json.getString("loadBalancingLabels").trim();
		loadBalancingLabels.clear();
		for ( String label : loadBalancingLabelsString.split(RESOURCES_SPLIT_REGEX) ) {
			loadBalancingLabels.add(label);
		}

		useResourcesEvenly = json.getBoolean("useResourcesEvenly");

		List<LockableResource> newResouces = req.bindJSONToList(
				LockableResource.class, json.get("resources"));
		for (LockableResource r : newResouces) {
			LockableResource old = fromName(r.getName());
			if (old != null) {
				r.setBuild(old.getBuild());
				r.setQueued(r.getQueueItemId(), r.getQueueItemProject());
			}
		}
		resources.clear();
		resources.addAll(newResouces);
		save();
	}

	public static LockableResourcesManager get() {
		Jenkins jenkins = Jenkins.getInstance();
		if (jenkins != null) {
			return jenkins.getPlugin(LockableResourcesManager.class);
		} else {
			LOGGER.fine("Error, Jenkins could not be found, so no plugin!");
			return null;
		}
	}

	@Override
	public synchronized void load() throws IOException {
		super.load();
		buildCaches();
	}

	@Override
	public synchronized void save() {
		try {
			super.save();
			buildCaches();
		}
		catch ( IOException ex ) {
			LOGGER.log(Level.SEVERE, "Unable to save configuration!", ex);
		}
	}

	private synchronized void buildCaches() {
		labelsCache.clear();
		lbLabelsCache.clear();
		resourceMapCache.clear();
		for ( LockableResource r : resources ) {
			boolean foundLbLabel = false;
			for ( String label : r.getLabelSet() ) {
				if ( !labelsCache.containsKey(label) ) labelsCache.put(label, new HashSet<LockableResource>());
				labelsCache.get(label).add(r);

				if (loadBalancingLabels.contains(label)) {
					foundLbLabel = true;
					if ( !lbLabelsCache.containsKey(label) ) lbLabelsCache.put(label, new HashSet<LockableResource>());
					lbLabelsCache.get(label).add(r);
				}
			}
			if ( !foundLbLabel ) {
				if ( !lbLabelsCache.containsKey(null) ) lbLabelsCache.put(null, new HashSet<LockableResource>());
				lbLabelsCache.get(null).add(r);
			}
			resourceMapCache.put(r.getName(), r);
		}
	}

	private synchronized double calculateLbLabelUsage( String label ) {
		int used = 0;
		for ( LockableResource r : lbLabelsCache.get(label) ) {
			if ( !r.isFree() ) used++;
		}
		return (double)used / lbLabelsCache.get(label).size();
	}

	@Override
	protected XmlFile getConfigXml() {
		File f = new File(Jenkins.getInstance().getRootDir(), this.getClass().getName() + ".xml");
		return new XmlFile(Jenkins.XSTREAM, f);
	}

}
