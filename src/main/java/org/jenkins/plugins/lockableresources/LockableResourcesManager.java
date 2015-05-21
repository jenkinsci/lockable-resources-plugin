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

import hudson.Extension;
import hudson.model.AbstractBuild;

import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;

import org.kohsuke.stapler.StaplerRequest;

@Extension
public class LockableResourcesManager extends GlobalConfiguration {
	
	private static final transient Random rand = new Random();

	private final LinkedHashSet<String> loadBalancingLabels;
	private boolean useResourcesEvenly = false;
	private final LinkedHashSet<LockableResource> resources;

	private final transient Map<String,Set<LockableResource>> lbLabelsCache = new HashMap<String,Set<LockableResource>>();

	public LockableResourcesManager() {
		resources = new LinkedHashSet<LockableResource>();
		loadBalancingLabels = new LinkedHashSet<String>();
		load();
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

	public Boolean isValidLabel(String label)
	{
		return this.getAllLabels().contains(label);
	}

	public Set<String> getAllLabels()
	{
		Set<String> labels = new HashSet<String>();
		for (LockableResource r : this.resources) {
			String rl = r.getLabels();
			if (rl == null || "".equals(rl))
				continue;
			labels.addAll(Arrays.asList(rl.split("\\s+")));
		}
		return labels;
	}

	public int getFreeResourceAmount(String label)
	{
		int free = 0;
		for (LockableResource r : this.resources) {
			if (r.isLocked() || r.isQueued() || r.isReserved())
				continue;
			if (Arrays.asList(r.getLabels().split("\\s+")).contains(label))
				free += 1;
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
		if (resourceName != null) {
			for (LockableResource r : resources) {
				if (resourceName.equals(r.getName())) {
					return r;
				}
			}
		}
		return null;
	}

	public synchronized boolean queue(List<LockableResource> resources, int queueItemId) {
		for (LockableResource r : resources) {
			if (r.isReserved() || r.isQueued(queueItemId) || r.isLocked()) {
				return false;
			}
		}
		for (LockableResource r : resources) {
			r.setQueued(queueItemId);
		}
		return true;
	}

	public synchronized Collection<LockableResource> queue(LockableResourcesStruct requiredResources,
	                                                 int queueItemId,
	                                                 String queueItemProject,
	                                                 int number,  // 0 means all
	                                                 Logger log) {
		// using a TreeSet here to ensure consistant ordering in logging/messaging output
		Set<LockableResource> selected = new TreeSet<LockableResource>();

		if (!checkCurrentResourcesStatus(selected, queueItemProject, queueItemId, log)) {
			// The project has another buildable item waiting -> bail out
			log.log(Level.FINEST, "{0} has another build waiting resources." +
			        " Waiting for it to proceed first.",
			        new Object[]{queueItemProject});
			return null;
		}

		List<LockableResource> candidates;
		if (requiredResources.label != null && requiredResources.label.isEmpty()) {
			candidates = requiredResources.required;
		} else {
			candidates = getResourcesWithLabel(requiredResources.label);
		}

		int required_amount = number == 0 ? candidates.size() : number;

		// only use fancy logic if we don't need to lock all of them
		if ( required_amount < candidates.size() ) {
			List<LockableResource> availableCandidates = new ArrayList<LockableResource>();
			for (LockableResource rs : candidates) {
				if (!rs.isReserved() && !rs.isLocked() && !rs.isQueued())
					availableCandidates.add(rs);
			}

			if ( !loadBalancingLabels.isEmpty() ) {
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
				// now repeatedly select a candidate resource from the label with the lowest current usage
				boolean resourcesLeft = true;
				while ( selected.size() < required_amount && resourcesLeft ) {
					resourcesLeft = false;
					double lowestUsage = 2;
					String lowestUsageLabel = null;
					for ( String label : groups.keySet() ) {
						if ( groups.get(label).size() > 0 ) {
							double usage = calculateLabelUsage(label);
							if ( usage < lowestUsage ) {
								resourcesLeft = true;
								lowestUsage = usage;
								lowestUsageLabel = label;
							}
						}
					}
					if ( resourcesLeft ) {
						List<LockableResource> group = groups.get(lowestUsageLabel);
						LockableResource r = selectResourceToUse(group);
						group.remove(r);
						selected.add(r);
						r.setQueued(queueItemId, queueItemProject);
					}
				}
			}
			else {
				while ( selected.size() < required_amount && availableCandidates.size() > 0 ) {
					LockableResource r = selectResourceToUse(availableCandidates);
					availableCandidates.remove(r);
					selected.add(r);
				}
			}
		}
		else {
			selected.addAll(candidates);
		}

		// if did not get wanted amount or did not get all
		if (selected.size() != required_amount) {
			log.log(Level.FINEST, "{0} found {1} resource(s) to queue." +
			        "Waiting for correct amount: {2}.",
			        new Object[]{queueItemProject, selected.size(), required_amount});
			// just to be sure, clean up
			for (LockableResource x : resources) {
				if (x.getQueueItemProject() != null &&
				    x.getQueueItemProject().equals(queueItemProject))
					x.unqueue();
			}
			return null;
		}

		for (LockableResource rsc : selected) {
			rsc.setQueued(queueItemId, queueItemProject);
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
	private boolean checkCurrentResourcesStatus(Collection<LockableResource> selected,
	                                            String project,
	                                            int taskId,
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

	public synchronized boolean lock(List<LockableResource> resources,
			AbstractBuild<?, ?> build) {
		for (LockableResource r : resources) {
			if (r.isReserved() || r.isLocked()) {
				return false;
			}
		}
		for (LockableResource r : resources) {
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
	public synchronized boolean configure(StaplerRequest req, JSONObject json) {
		try {
			String loadBalancingLabelsString = json.getString("loadBalancingLabels").trim();
			loadBalancingLabels.clear();
			for ( String label : loadBalancingLabelsString.split("\\s+") ) {
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
			return true;
		} catch (JSONException e) {
			return false;
		}
	}

	public static LockableResourcesManager get() {
		return (LockableResourcesManager) Jenkins.getInstance()
				.getDescriptorOrDie(LockableResourcesManager.class);
	}

	@Override
	public synchronized void load() {
		super.load();
		buildLabelGroupsCache();
	}

	@Override
	public synchronized void save() {
		super.save();
		buildLabelGroupsCache();
	}

	private synchronized void buildLabelGroupsCache() {
		lbLabelsCache.clear();
		for ( LockableResource r : resources ) {
			boolean foundLabel = false;
			for ( String label : loadBalancingLabels ) {
				if ( r.isValidLabel(label) ) {
					foundLabel = true;
					if ( !lbLabelsCache.containsKey(label) ) lbLabelsCache.put(label, new HashSet<LockableResource>());
					lbLabelsCache.get(label).add(r);
				}
			}
			if ( !foundLabel ) {
				if ( !lbLabelsCache.containsKey(null) ) lbLabelsCache.put(null, new HashSet<LockableResource>());
				lbLabelsCache.get(null).add(r);
			}
		}
	}

	private synchronized double calculateLabelUsage( String label ) {
		int used = 0;
		for ( LockableResource r : lbLabelsCache.get(label) ) {
			if ( !r.isFree() ) used++;
		}
		return (double)used / lbLabelsCache.get(label).size();
	}

}
