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

import java.util.ArrayList;
import java.util.List;

import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.StaplerRequest;

@Extension
public class LockableResourcesManager extends GlobalConfiguration {

	private int defaultPriority;
	private String priorityParameterName;
	private List<LockableResource> resources;

	public LockableResourcesManager() {
		resources = new ArrayList<LockableResource>();
		defaultPriority = 5;
		priorityParameterName = "LOCK_PRIORITY";
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
			AbstractBuild<?, ?> rBuild = r.getBuild();
			if (rBuild != null && rBuild == build) {
				matching.add(r);
			}
		}
		return matching;
	}

	public int getDefaultPriority() {
		return defaultPriority;
	}

	public String getPriorityParameterName() {
		return priorityParameterName;
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
			int queueItemId) {
		for (LockableResource r : resources)
			if (r.isReserved() || r.isQueued(queueItemId) || r.isLocked())
				return false;
		for (LockableResource r : resources)
			r.setQueueItemId(queueItemId);
		return true;
	}

	public synchronized List<LockableResource> queue(List<LockableResource> resources,
			int queueItemId, String queueItemProject, int number) {

		List<LockableResource> selected = new ArrayList<LockableResource>();

		for (LockableResource r : resources) {
			// This project might already have something in queue
			String rProject = r.getQueueItemProject();
			if (rProject != null && rProject.equals(queueItemProject)) {
				if (r.isQueuedByTask(queueItemId)) {
					// this item has queued the resource earlier
					selected.add(r);
				} else {
					// The project has another buildable item waiting -> bail out
					return null;
				}
			}
		}

		for (LockableResource rs : resources) {
			if (selected.size() >= number) {
				break;
			}
			if (!rs.isReserved() && !rs.isLocked() && !rs.isQueued()) {
				selected.add(rs);
			}
		}

		if (selected.size() != number) {
			// just to be sure, clean up
			for (LockableResource x : resources) {
				if (x.getQueueItemProject() != null
						&& x.getQueueItemProject().equals(queueItemProject)) {
					x.setQueueItemProject(null);
					x.setQueueItemId(LockableResource.NOT_QUEUED);
				}
			}
			return null;
		}

		for (LockableResource rsc : selected) {
			rsc.setQueueItemId(queueItemId);
			rsc.setQueueItemProject(queueItemProject);
		}

		return selected;
	}

	public synchronized boolean lock(List<LockableResource> resources,
			AbstractBuild<?, ?> build) {
		for (LockableResource r : resources)
			if (r.isReserved() || r.isLocked())
				return false;
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
				r.setQueueItemProject(null);
				r.setQueueItemId(LockableResource.NOT_QUEUED);
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

	@Override
	public boolean configure(StaplerRequest req, JSONObject json)
			throws FormException {
		try {
			defaultPriority = json.getInt("defaultPriority");
			priorityParameterName = json.getString("priorityParameterName");
			List<LockableResource> newResouces = req.bindJSONToList(
					LockableResource.class, json.get("resources"));
			for (LockableResource r : newResouces) {
				LockableResource old = fromName(r.getName());
				if (old != null) {
					r.setBuild(old.getBuild());
					r.setQueueItemId(r.getQueueItemId());
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
