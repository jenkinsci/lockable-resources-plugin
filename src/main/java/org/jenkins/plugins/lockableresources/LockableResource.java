/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.infradna.tool.bridge_method_injector.WithBridgeMethods;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import org.jenkinsci.plugins.workflow.steps.StepContext;

import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Queue;
import hudson.model.Queue.Item;
import hudson.model.Queue.Task;
import hudson.model.Run;
import hudson.model.User;
import hudson.tasks.Mailer.UserProperty;

import javax.annotation.CheckForNull;

@ExportedBean(defaultVisibility = 999)
public class LockableResource extends AbstractDescribableImpl<LockableResource> implements Serializable {

	private static final long serialVersionUID = 1L;

	private static final Logger LOGGER = Logger.getLogger(LockableResource.class.getName());

	public static final int NOT_QUEUED = 0;

	public static final String GROOVY_LABEL_MARKER = "groovy:";

	private static final int QUEUE_TIMEOUT = 60;

	private final String name;
	private String description = "";
	private String labels = "";
	private String reservedBy = null;

	private long queueItemId = NOT_QUEUED;
	private String queueItemProject = null;
	private transient Run<?, ?> build = null;
	// Needed to make the state non-transient
	private String buildExternalizableId = null;
	private long queuingStarted = 0;

	/**
	 * Only used when this lockable resource is tried to be locked by {@link LockStep},
	 * otherwise (freestyle builds) regular Jenkins queue is used.
	 */
	private List<StepContext> queuedContexts = new ArrayList<>();

	@DataBoundConstructor
	public LockableResource(String name) {
		this.name = name;
	}

	private Object readResolve() {
		if (queuedContexts == null) { // this field was added after the initial version if this class
			queuedContexts = new ArrayList<>();
		}

		return this;
	}

	@DataBoundSetter
	public void setDescription(String description) {
		this.description = description;
	}

	@DataBoundSetter
	public void setLabels(String labels) {
		this.labels = labels;
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


	public Integer getContextsInQueue() {
		return queuedContexts.size();
	}

	public boolean isValidLabel(String candidate, Map<String, Object> params) {
		return candidate.startsWith(GROOVY_LABEL_MARKER) ? expressionMatches(candidate, params) : labelsContain(candidate);
	}

	private boolean labelsContain(String candidate) {
		return makeLabelsList().contains(candidate);
	}

	private List<String> makeLabelsList() {
		return Arrays.asList(labels.split("\\s+"));
	}

	private boolean expressionMatches(String expression, Map<String, Object> params) {
		Binding binding = new Binding(params);

		binding.setVariable("resourceName", name);
		binding.setVariable("resourceDescription", description);
		binding.setVariable("resourceLabels", makeLabelsList());

		String expressionToEvaluate = expression.replace(GROOVY_LABEL_MARKER, "");

		GroovyShell shell = new GroovyShell(binding);

		try {
			Object result = shell.evaluate(expressionToEvaluate);

			if (LOGGER.isLoggable(Level.FINE)) {
				LOGGER.fine("Checked resource " + name + " for " + expression + " with " + binding + " -> " + result);
			}

			return (Boolean) result;
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "Cannot get boolean result out of groovy expression '"
							+ expressionToEvaluate + "' on (" + binding + ")", e);

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
			Jenkins jenkins = Jenkins.getInstance();

			if (jenkins == null) {
				throw new IllegalStateException("Jenkins instance has not been started or was already shut down.");
			}

			User user = jenkins.getUser(reservedBy);

			if (user != null) {
				UserProperty email = user.getProperty(UserProperty.class);

				if (email != null) {
					return email.getAddress();
				}
			}
		}

		return null;
	}

	public boolean isQueued() {
		this.validateQueuingTimeout();
		return queueItemId != NOT_QUEUED;
	}

	// returns True if queued by any other task than the given one
	public boolean isQueued(long taskId) {
		this.validateQueuingTimeout();
		return queueItemId != NOT_QUEUED && queueItemId != taskId;
	}

	public boolean isQueuedByTask(long taskId) {
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
		return getBuild() != null;
	}

	/**
	 * Resolve the lock cause for this resource. It can be reserved or locked.
	 *
	 * @return the lock cause or null if not locked
	 */
	@CheckForNull
	public String getLockCause() {
		if (isReserved()) {
			return String.format("[%s] is reserved by %s", name, reservedBy);
		}
		if (isLocked()) {
			return String.format("[%s] is locked by %s", name, buildExternalizableId);
		}
		return null;
	}

	@WithBridgeMethods(value = AbstractBuild.class, adapterMethod = "getAbstractBuild")
	public Run<?, ?> getBuild() {
		if (build == null && buildExternalizableId != null) {
			build = Run.fromExternalizableId(buildExternalizableId);
		}
		return build;
	}

	/**
	 * @see WithBridgeMethods
	 */
	@Deprecated
	private Object getAbstractBuild(final Run owner, final Class targetClass) {
		return owner instanceof AbstractBuild ? (AbstractBuild) owner : null;
	}

	@Exported
	public String getBuildName() {
		if (getBuild() != null)
			return getBuild().getFullDisplayName();
		else
			return null;
	}

	public void setBuild(Run<?, ?> lockedBy) {
		this.build = lockedBy;
		if (lockedBy != null) {
			this.buildExternalizableId = lockedBy.getExternalizableId();
		} else {
			this.buildExternalizableId = null;
		}
	}

	public Task getTask() {
		Item item = Queue.getInstance().getItem(queueItemId);
		if (item != null) {
			return item.task;
		} else {
			return null;
		}
	}

	public long getQueueItemId() {
		this.validateQueuingTimeout();
		return queueItemId;
	}

	public String getQueueItemProject() {
		this.validateQueuingTimeout();
		return this.queueItemProject;
	}

	public void setQueued(long queueItemId) {
		this.queueItemId = queueItemId;
		this.queuingStarted = System.currentTimeMillis() / 1000;
	}

	public void setQueued(long queueItemId, String queueProjectName) {
		this.setQueued(queueItemId);
		this.queueItemProject = queueProjectName;
	}

	private void validateQueuingTimeout() {
		if (queuingStarted > 0) {
			long now = System.currentTimeMillis() / 1000;
			if (now - queuingStarted > QUEUE_TIMEOUT) {
				unqueue();
			}
		}
	}

	public void queueAdd(StepContext context) {
		queuedContexts.add(context);
	}

	/**
	 * Returns the next context (if exists) waiting to get the lock.
	 * It removes the returned context from the queue.
	 */
	@CheckForNull
	/* package */ StepContext getNextQueuedContext(boolean inversePrecedence) {
		if (queuedContexts.size() > 0) {
			if (!inversePrecedence) {
				return queuedContexts.remove(0);
			} else {
				long newest = 0;
				int index = 0;
				int newestIndex = 0;
				for (Iterator<StepContext> iterator = queuedContexts.iterator(); iterator.hasNext(); ) {
					StepContext c = iterator.next();
					try {
						Run<?, ?> run = c.get(Run.class);
						if (run.getStartTimeInMillis() > newest) {
							newest = run.getStartTimeInMillis();
							newestIndex = index;
						}
					} catch (Exception e) {
						// skip this one and remove from queue
						iterator.remove();
						LOGGER.log(Level.FINE, "Skipping queued context as it does not hold a Run object", e);
					}
					index++;
				}

				return queuedContexts.remove(newestIndex);
			}
		}
		return null;
	}

	/* package */ boolean remove(StepContext context) {
		return queuedContexts.remove(context);
	}

	@DataBoundSetter
	public void setReservedBy(String userName) {
		this.reservedBy = Util.fixEmptyAndTrim(userName);
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
