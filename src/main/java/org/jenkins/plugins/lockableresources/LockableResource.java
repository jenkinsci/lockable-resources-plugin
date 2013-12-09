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
import hudson.model.AbstractDescribableImpl;
import hudson.model.AbstractBuild;
import hudson.model.Descriptor;
import hudson.model.Queue;
import hudson.model.Queue.Item;
import hudson.model.Queue.Task;

import java.util.ArrayList;
import java.util.List;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.bind.JavaScriptMethod;

public class LockableResource extends AbstractDescribableImpl<LockableResource> {

	private static final int NOBODY = 0;

	private final String name;
	private final String description;

	private transient int reservedBy = NOBODY;
	private transient AbstractBuild<?, ?> lockedBy = null;

	@DataBoundConstructor
	public LockableResource(String name, String description) {
		this.name = name;
		this.description = description;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public boolean isReserved(int requestorId) {
		return reservedBy != NOBODY && reservedBy != requestorId;
	}

	public void unreserve() {
		reservedBy = NOBODY;
	}

	public boolean isLocked() {
		return lockedBy != null;
	}

	public AbstractBuild<?, ?> getLockedBy() {
		return lockedBy;
	}

	public void setLockedBy(AbstractBuild<?, ?> lockedBy) {
		this.lockedBy = lockedBy;
	}

	public Task getTask() {
		Item item = Queue.getInstance().getItem(reservedBy);
		if (item != null) {
			return item.task;
		} else {
			return null;
		}
	}

	public int getReservedBy() {
		return reservedBy;
	}

	public void setReservedBy(int queueItemId) {
		this.reservedBy = queueItemId;
	}

	@JavaScriptMethod
	public void forceUnlock() {
		List<LockableResource> resources = new ArrayList<LockableResource>();
		resources.add(this);
		LockableResourcesManager.get().unlock(resources, null);
	}

	@Override
	public String toString() {
		return name;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		LockableResource other = (LockableResource) obj;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		return true;
	}

	@Extension
	public static class DescriptorImpl extends Descriptor<LockableResource> {

		@Override
		public String getDisplayName() {
			return "Resource";
		}

	}
}
