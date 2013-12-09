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

	public int getDefaultPriority() {
		return defaultPriority;
	}

	public String getPriorityParameterName() {
		return priorityParameterName;
	}

	public synchronized boolean reserve(List<LockableResource> resources,
			int queueItemId) {
		for (LockableResource r : resources)
			if (r.isReserved(queueItemId) || r.isLocked())
				return false;
		for (LockableResource r : resources)
			r.setReservedBy(queueItemId);
		return true;
	}

	public synchronized boolean lock(List<LockableResource> resources,
			AbstractBuild<?, ?> build) {
		for (LockableResource r : resources)
			if (r.isLocked())
				return false;
		for (LockableResource r : resources) {
			r.unreserve();
			r.setLockedBy(build);
		}
		return true;
	}

	public synchronized void unlock(List<LockableResource> resources,
			AbstractBuild<?, ?> build) {
		for (LockableResource r : resources) {
			if (build == null || build == r.getLockedBy()) {
				r.unreserve();
				r.setLockedBy(null);
			}
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
			defaultPriority = json.getInt("defaultPriority");
			priorityParameterName = json.getString("priorityParameterName");
			resources = req.bindJSONToList(LockableResource.class,
					json.get("resources"));
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
