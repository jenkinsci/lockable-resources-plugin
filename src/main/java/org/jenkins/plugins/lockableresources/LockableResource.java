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
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AbstractBuild;
import hudson.model.Descriptor;
import hudson.model.Queue;
import hudson.model.Queue.Item;
import hudson.model.Queue.Task;
import java.util.Arrays;

import org.kohsuke.stapler.DataBoundConstructor;

public class LockableResource extends AbstractDescribableImpl<LockableResource> {

	public static final int NOT_QUEUED = 0;
        // Queueing cannot be eradicated without rewriting too much
        // because LockableResourcesManager.getResourcesFromProject depends on it
	private static final int QUEUE_TIMEOUT = 5;

	private final String name;
	private final String description;
	private final String labels;
	private String reservedBy;

	private transient int queueItemId = NOT_QUEUED;
	private transient String queueItemProject = null;
	private transient AbstractBuild<?, ?> build = null;
	private transient long queuingStarted = 0;

	@DataBoundConstructor
	public LockableResource(
			String name, String description, String labels, String reservedBy) {
		this.name = name;
		this.description = description;
		this.labels = labels;
		this.reservedBy = Util.fixEmptyAndTrim(reservedBy);
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public String getLabels() {
		return labels;
	}

	public Boolean isValidLabel(String candidate) {
		return Arrays.asList(labels.split("\\s+")).contains(candidate);
	}

	public String getReservedBy() {
		return reservedBy;
	}

	public boolean isReserved() {
		return reservedBy != null;
	}

	public boolean isQueued() {
		this.validateQueuingTimeout();
		return queueItemId != NOT_QUEUED;
	}

	// returns True if queued by any other task than the given one
	public boolean isQueued(int taskId) {
		this.validateQueuingTimeout();
		return queueItemId != NOT_QUEUED && queueItemId != taskId;
	}

	public boolean isQueuedByTask(int taskId) {
		this.validateQueuingTimeout();
		return queueItemId == taskId;
	}

	public void unqueue() {
		queueItemId = NOT_QUEUED;
		queueItemProject = null;
		queuingStarted = 0;
	}

	public boolean isLocked() {
		return build != null;
	}

	public AbstractBuild<?, ?> getBuild() {
		return build;
	}

	public void setBuild(AbstractBuild<?, ?> lockedBy) {
		this.build = lockedBy;
	}

	public Task getTask() {
		Item item = Queue.getInstance().getItem(queueItemId);
		if (item != null) {
			return item.task;
		} else {
			return null;
		}
	}

	public int getQueueItemId() {
		this.validateQueuingTimeout();
		return queueItemId;
	}

	public String getQueueItemProject() {
		this.validateQueuingTimeout();
		return this.queueItemProject;
	}

	public void setQueued(int queueItemId) {
		this.queueItemId = queueItemId;
		this.queuingStarted = System.currentTimeMillis() / 1000;
	}

	public void setQueued(int queueItemId, String queueProjectName) {
		this.setQueued(queueItemId);
		this.queueItemProject = queueProjectName;
	}

	private void validateQueuingTimeout() {
		if (queuingStarted > 0) {
			long now = System.currentTimeMillis() / 1000;
			if (now - queuingStarted > QUEUE_TIMEOUT)
				unqueue();
		}
	}

	public void setReservedBy(String userName) {
		this.reservedBy = userName;
	}

	public void unReserve() {
		this.reservedBy = null;
	}

	public void reset() {
		this.unReserve();
		this.unqueue();
		this.setBuild(null);
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
