/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AbstractBuild;
import hudson.model.Descriptor;
import hudson.model.Queue;
import hudson.model.Queue.Item;
import hudson.model.Queue.Task;
import hudson.model.User;
import hudson.tasks.Mailer.UserProperty;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

@ExportedBean(defaultVisibility = 999)
public class LockableResource extends AbstractDescribableImpl<LockableResource> {

	private static final Logger LOGGER = Logger.getLogger(LockableResource.class.getName());
	public static final int NOT_QUEUED = 0;
	private static final int QUEUE_TIMEOUT = 60;
	public static final String GROOVY_LABEL_MARKER = "groovy:";

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

	@Exported
	public String getName() {
		return name;
	}

	@Exported
	public String getDescription() {
		return description;
	}

	@Exported
	public String getLabels() {
		return labels;
	}

	public boolean isValidLabel(String candidate, Map<String, Object> params) {
		return candidate.startsWith(GROOVY_LABEL_MARKER) ? expressionMatches(
				candidate, params) : labelsContain(candidate);
	}

	private boolean labelsContain(String candidate) {
		return makeLabelsList().contains(candidate);
	}

	private List<String> makeLabelsList() {
		return Arrays.asList(labels.split("\\s+"));
	}

	private boolean expressionMatches(String expression,
			Map<String, Object> params) {
		Binding binding = new Binding(params);
		binding.setVariable("resourceName", name);
		binding.setVariable("resourceDescription", description);
		binding.setVariable("resourceLabels", makeLabelsList());
		String expressionToEvaluate = expression.replace(GROOVY_LABEL_MARKER, "");
		GroovyShell shell = new GroovyShell(binding);
		try {
			Object result = shell.evaluate(expressionToEvaluate);
			if (LOGGER.isLoggable(Level.FINE)) {
				LOGGER.fine("Checked resource " + name + " for " + expression
						+ " with " + binding + " -> " + result);
			}
			return (Boolean) result;
		} catch (Exception e) {
			LOGGER.log(
					Level.SEVERE,
					"Cannot get boolean result out of groovy expression '"
							+ expressionToEvaluate + "' on (" + binding + ")",
					e);
			return false;
		}
	}

	@Exported
	public String getReservedBy() {
		return reservedBy;
	}

	@Exported
	public boolean isReserved() {
		return reservedBy != null;
	}

	@Exported
	public String getReservedByEmail() {
		if (reservedBy != null) {
			UserProperty email = null;
			User user = Jenkins.getInstance().getUser(reservedBy);
			if (user != null)
				email = user.getProperty(UserProperty.class);
			if (email != null)
				return email.getAddress();
		}
		return null;
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

	@Exported
	public boolean isLocked() {
		return build != null;
	}

	public AbstractBuild<?, ?> getBuild() {
		return build;
	}

	@Exported
	public String getBuildName() {
		if (build != null)
			return build.getFullDisplayName();
		else
			return null;
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
