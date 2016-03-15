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
import hudson.model.AbstractBuild;
import hudson.model.Run;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

	@Deprecated
	private transient int defaultPriority;
	@Deprecated
	private transient String priorityParameterName;
	private List<LockableResource> resources;

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

	public List<LockableResource> getResourcesFromBuild(AbstractBuild<?, ?> build) {
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
				|| this.getAllLabels().contains(label);
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

	public List<LockableResource> getResourcesWithLabel(String label,
			Map<String, Object> params) {
		List<LockableResource> found = new ArrayList<LockableResource>();
		for (LockableResource r : this.resources) {
			if (r.isValidLabel(label, params))
				found.add(r);
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
		List<LockableResource> selected = new ArrayList<LockableResource>();

		if (!checkCurrentResourcesStatus(selected, queueItemProject, queueItemId, log)) {
			// The project has another buildable item waiting -> bail out
			log.log(Level.FINEST, "{0} has another build waiting resources." +
			        " Waiting for it to proceed first.",
			        new Object[]{queueItemProject});
			return null;
		}

		List<LockableResource> candidates = new ArrayList<LockableResource>();
		if (requiredResources.label != null && requiredResources.label.isEmpty()) {
			candidates = requiredResources.required;
		} else {
			candidates = getResourcesWithLabel(requiredResources.label, params);
		}

		for (LockableResource rs : candidates) {
			if (number != 0 && (selected.size() >= number))
				break;
			if (!rs.isReserved() && !rs.isLocked() && !rs.isQueued())
				selected.add(rs);
		}

		// if did not get wanted amount or did not get all
		int required_amount = number == 0 ? candidates.size() : number;

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

	public synchronized boolean lock(List<LockableResource> resources,
			Run<?, ?> build) {
		for (LockableResource r : resources) {
			if (r.isReserved() || r.isLocked()) {
				return false;
			}
		}
		for (LockableResource r : resources) {
			r.unqueue();
			r.setBuild(build);
			r.removeFromQueue(build);
			save();
		}
		return true;
	}

	public synchronized boolean isNextInQueue(List<LockableResource> resources, Run<?, ?> build) {
		for (LockableResource r : resources) {
			if (!r.isNextInQueue(build)) {
				return false;
			}
		}
		return true;
	}

	public synchronized void addToQueue(List<LockableResource> resources, Run<?, ?> build) {
		for (LockableResource r : resources) {
			r.addToQueue(build);
		}
		save();
	}

	public synchronized void removeFromQueue(List<LockableResource> resources, Run<?, ?> build) {
		for (LockableResource r : resources) {
			r.removeFromQueue(build);
		}
		save();
	}

	public synchronized void unlock(List<LockableResource> resourcesToUnLock,
			Run<?, ?> build) {
		for (LockableResource r : resourcesToUnLock) {
			// Seach the resource in the internal list un unlock it
			for (LockableResource internal : resources) {
				if (internal.getName().equals(r.getName())) {
					if (build == null || build.getExternalizableId().equals(internal.getBuild().getExternalizableId())) {
						internal.unqueue();
						internal.setBuild(null);
						save();
					}
				}
			}
		}
	}

	public synchronized String getLockCause(String r) {
		return new LockableResourcesStruct(r).required.get(0).getLockCause();
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

	public static LockableResourcesManager get() {
		return (LockableResourcesManager) Jenkins.getInstance()
				.getDescriptorOrDie(LockableResourcesManager.class);
	}

}
