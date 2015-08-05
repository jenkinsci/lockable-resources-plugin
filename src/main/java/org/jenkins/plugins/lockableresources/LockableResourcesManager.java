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

	/**
	 * @return A list containing all resources
	 */
	public List<LockableResource> getResources() {
		return resources;
	}

	/**
	 * @param fullName
	 * @return A list of all resources that are queued by a project with the
	 * given name
	 */
	public List<LockableResource> getResourcesFromProject(String fullName) {
		List<LockableResource> matching = new ArrayList<LockableResource>();
		for (LockableResource r : resources) {
			String rName = r.getQueueItemProject();
			if (rName != null && rName.equals(fullName))
				matching.add(r);
		}
		return matching;
	}

	/**
	 * @param build
	 * @return A list of all resources locked by given build
	 */
	public List<LockableResource> getResourcesFromBuild(AbstractBuild<?, ?> build) {
		List<LockableResource> matching = new ArrayList<LockableResource>();
		for (LockableResource r : resources) {
			AbstractBuild<?, ?> rBuild = r.getBuild();
			if (rBuild != null && rBuild == build)
				matching.add(r);
		}

		return matching;
	}

	/**
	 * @param label
	 * @return True if the given label is in the list of labels for any
	 * resource, or false otherwise
	 */
	public Boolean isValidLabel(String label) {
		return label.startsWith(LockableResource.GROOVY_LABEL_MARKER)
				|| this.getAllLabels().contains(label);
	}

	/**
	 * @return Creates a set of labels containing all the labels associated with
	 * the resources in the 'resources' field
	 */
	public Set<String> getAllLabels() {
		Set<String> labels = new HashSet<String>();
		for (LockableResource r : this.resources) {
			String rl = r.getLabels();
			if (rl == null || "".equals(rl))
				continue;
			labels.addAll(Arrays.asList(rl.split("\\s+")));
		}
		return labels;
	}

	/**
	 * @param label
	 * @return The amount of resources with the given label that are not locked, queued or reserved
	 */
	public int getFreeResourceAmount(String label) {
		int free = 0;
		for (LockableResource r : this.resources) {
			if (r.isLocked() || r.isQueued() || r.isReserved())
				continue;

			if (r.isValidLabel(label, null))
				free += 1;
		}
		return free;
	}

	/**
	 * @param label
	 * @param params
	 * @return A list containing the resources that have the given label
	 */
	public List<LockableResource> getResourcesWithLabel(String label,
														Map<String, Object> params) {
		List<LockableResource> found = new ArrayList<LockableResource>();
		for (LockableResource r : this.resources)
			if (r.isValidLabel(label, params))
				found.add(r);

		return found;
	}

	/**
	 * @param resourceName
	 * @return The resource with the given name
	 */
	public LockableResource fromName(String resourceName) {
		if (resourceName != null)
			for (LockableResource r : resources)
				if (resourceName.equals(r.getName()))
					return r;

		return null;
	}

	/**
	 * Queue the resources in 'resources' if they are not already
	 * locked, queued or reserved
	 * @param resources The resources that will be queued
	 * @param queueItemId The id of the item that enqueues the resources
	 * @return True if the resources have been successfully queued, or false
	 * otherwise
	 */
	public synchronized boolean queue(  List<LockableResource> resources,
										int queueItemId) {
		for (LockableResource r : resources)
			if (r.isReserved() || r.isQueued(queueItemId) || r.isLocked())
				return false;
		for (LockableResource r : resources)
			r.setQueued(queueItemId);
		return true;
	}

	/**
	 * First find available resources, and then queue them for the
	 * given queueItemProject
	 * @param requiredResources A pool of resources that will be investigated
	 * @param queueItemId The ID of the current item
	 * @param queueItemProject The current project
	 * @param number The number of resources requested - 0 means all
	 * @param params Parameters used to identify labels
	 * @param log The Logger file used for logging
	 * @return A list of queued resources that have either been previously selected in
	 * the previous queuing round, or resources that can be queued - are not
	 * reserved for any user, locked or queued (by another item). If the list does not
	 * meet the size criteria, the method will return null.
	 */
	public synchronized List<LockableResource> queue(LockableResourcesStruct requiredResources,
	                                                 int queueItemId,
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
		if (requiredResources.label != null && requiredResources.label.isEmpty())
			candidates = requiredResources.required;
		else
			candidates = getResourcesWithLabel(requiredResources.label, params);

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

		for (LockableResource rsc : selected)
			rsc.setQueued(queueItemId, queueItemProject);
		return selected;
	}

	/**
	 * Adds already selected (in previous queue round) resources to 'selected'
	 *
	 * @return false if another item queued for this project -> bail out
	 */
	private boolean checkCurrentResourcesStatus(List<LockableResource> selected,
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

	/**
	 * @param resources The resources to lock
	 * @param build The build that locks the resources
	 * @return True if all the resources have been successfully dequeued and
	 * locked (in order to be able to achieve this, none of the resources can
	 * be reserved by an user or locked). Return false, if the process failed
	 */
	public synchronized boolean lock(List<LockableResource> resources,
									AbstractBuild<?, ?> build) {
		for (LockableResource r : resources) {
			if (r.isReserved() || r.isLocked())
				return false;
		}

		for (LockableResource r : resources) {
			r.unqueue();
			r.setBuild(build);
		}
		return true;
	}

	/**
	 * Unlock all resources in the list that were locked by the given build
	 * @param resources The resources that will be unlocked
	 * @param build The build that locks the resources
	 */
	public synchronized void unlock(List<LockableResource> resources,
									AbstractBuild<?, ?> build) {
		for (LockableResource r : resources) {
			if (build == null || build == r.getBuild()) {
				r.unqueue();
				r.setBuild(null);
			}
		}
	}

	/**
	 * @param resources The resources that will be reserved
	 * @param userName The name of the user that reserves the resources
	 * @return True if all the resources have been successfully reserved
	 * (to achieve this, none of the resources can be reserved by an user or locked).
	 * Return false, if the process failed
	 */
	public synchronized boolean reserve(List<LockableResource> resources,
										String userName) {
		for (LockableResource r : resources) {
			if (r.isReserved() || r.isLocked() || r.isQueued())
				return false;
		}

		for (LockableResource r : resources)
			r.setReservedBy(userName);

		save();
		return true;
	}

	/**
	 * @param resources Unreserve all the resources in the given list
	 */
	public synchronized void unreserve(List<LockableResource> resources) {
		for (LockableResource r : resources)
			r.unReserve();

		save();
	}

	@Override
	public String getDisplayName() {
		return "External Resources";
	}

	/**
	 * Reset the resources in the given list (calls resource.reset)
	 * @param resources
	 */
	public synchronized void reset(List<LockableResource> resources) {
		for (LockableResource r : resources)
			r.reset();

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
					r.setQueued(old.getQueueItemId(), old.getQueueItemProject());
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
