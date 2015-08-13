/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                       *
 *                                                                           *
 * Resource reservation per node by Darius Mihai (mihai_darius22@yahoo.com)  *
 * Copyright (C) 2015 Freescale Semiconductor, Inc.                          *
 *                                                                           *
 * This file is part of the Jenkins Lockable Resources Plugin and is         *
 * published under the MIT license.                                          *
 *                                                                           *
 * See the "LICENSE.txt" file for more information.                          *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AbstractBuild;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Queue.Item;
import hudson.model.Queue.Task;
import hudson.model.User;
import hudson.tasks.Mailer.UserProperty;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Set;

import jenkins.model.Jenkins;

import org.jenkins.plugins.lockableresources.queue.Utils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

@ExportedBean(defaultVisibility = 999)
public class LockableResource extends AbstractDescribableImpl<LockableResource> {

	private static final Logger LOGGER = Logger.getLogger(LockableResource.class.getName());
	public static final int NOT_QUEUED = 0;
	private static final int QUEUE_TIMEOUT = 60;
	public static final String GROOVY_LABEL_MARKER = "groovy:";

	/** The name of this resource */
	private final String name;
	/** The description of this resource */
	private final String description;
	/** Labels associated with this resource */
	private final String labels;
	/** The name(s) of the nodes that can use this resource */
	private String reservedForNodes;
	/** The name of the user that should be logged in, in order to use this resource */
	private String reservedBy;

	/** The id of the item that queued this resource */
	private transient int queueItemId = NOT_QUEUED;
	/** The name of the project that queued this resource */
	private transient String queueItemProject = null;
	/** The build that locked this resource */
	private transient AbstractBuild<?, ?> build = null;
	/** The moment (UNIX time in seconds) when the resource was queued */
	private transient long queuingStarted = 0;
	/** A set of node names (slave names) that can use this resource; same as
	 *  reservedForNodes, but names are split by whitespace
	 */
	private transient Set<String> reservedForNodesSet;

	@DataBoundConstructor
	public LockableResource(String name,
							String description,
							String labels,
							String reservedForNodes,
							String reservedBy) {
		this.name = name;
		this.description = description;
		this.labels = labels;
		this.reservedBy = Util.fixEmptyAndTrim(reservedBy);
		this.reservedForNodes = Util.fixEmptyAndTrim(reservedForNodes);

		this.reservedForNodesSet = new WhitespaceSet(reservedForNodes);
	}

	/**
	 * @return The name of this resource
	 */
	@Exported
	public String getName() {
		return name;
	}

	/**
	 * @return The description for this resource
	 */
	@Exported
	public String getDescription() {
		return description;
	}

	/**
	 * @return The 'labels' string associated with this resource
	 */
	@Exported
	public String getLabels() {
		return labels;
	}

	/**
	 * @return A string with the names of the nodes that reserved this resource,
	 * or null if the resource is not reserved for any node
	 */
	@Exported
	public String getReservedForNodes() {
		return reservedForNodes;
	}

	/**
	 * @return A set of names of the nodes that reserved this resource, or null
	 * if the resource is not reserved for any node
	 */
	public Set<String> getReservedForNodesSet() {
		return reservedForNodesSet;
	}

	/**
	 * @param candidate
	 * @param params
	 * @return True if the label either matches a given groovy expression in
	 * the 'params' field or the 'candidate' parameter is among this resource's labels.
	 * Return false otherwise
	 */
	public boolean isValidLabel(String candidate, Map<String, Object> params) {
		return candidate.startsWith(GROOVY_LABEL_MARKER) ? expressionMatches(
				candidate, params) : labelsContain(candidate);
	}

	/**
	 * @param candidate
	 * @return True if the labels list contains the given string, or false otherwise
	 */
	private boolean labelsContain(String candidate) {
		return makeLabelsList().contains(candidate);
	}

	/**
	 * Splits the 'labels' field in words delimited by white space
	 * @return A list of labels based on the 'labels' string.
	 */
	private List<String> makeLabelsList() {
		return Arrays.asList(labels.split("\\s+"));
	}

	/**
	 * A binding variable is created based on the 'params' field and this resource's
	 * name, description and labels
	 * @param expression The expression (script) to check
	 * @param params The parameters that will be checked
	 * @return True if the script check against the binding is successful
	 */
	private boolean expressionMatches(  String expression,
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

	/**
	 * @return The name of the user that reserved this resource, or null it is
	 * not reserved by any user
	 */
	@Exported
	public String getReservedBy() {
		return reservedBy;
	}

	/**
	 * @param node
	 * @return True if this resource is not reserved for any node or if it is
	 * reserved for the given node, or false otherwise.
	 */
	public boolean isReservedForNode(Node node) {
		if (node == null)
			return isReservedForThisNode();

		String nodeName = node.getNodeName().equals("") ? "master" : node.getNodeName();

		return reservedForNodesSet.isEmpty() || reservedForNodesSet.contains(nodeName);
	}

	/**
	 * @return True if the node running this method is one of the nodes for
	 * which this resource has been reserved for, or false otherwise
	 */
	public boolean isReservedForThisNode() {
		return reservedForNodesSet.isEmpty() || reservedForNodesSet.contains(Utils.getNodeName());
	}

	/**
	 * @return True if the resource was reserved by an user, or false otherwise
	 */
	@Exported
	public boolean isReserved() {
		return reservedBy != null;
	}

	/**
	 * @return The e-mail address of the user that reserved this resource
	 */
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

	/**
	 * @return True if the resource is already queued, or false otherwise
	 */
	public boolean isQueued() {
		this.validateQueuingTimeout();
		return queueItemId != NOT_QUEUED;
	}

	/**
	 * @param taskId
	 * @return True if queued by any other task than the given one, or false
	 * otherwise
	 */
	public boolean isQueued(int taskId) {
		this.validateQueuingTimeout();
		return queueItemId != NOT_QUEUED && queueItemId != taskId;
	}

	/**
	 * @param taskId
	 * @return True if this resource is queued by the given task, or false
	 * otherwise
	 */
	public boolean isQueuedByTask(int taskId) {
		this.validateQueuingTimeout();
		return queueItemId == taskId;
	}

	/**
	 * Resets the variables queueItemId, queueItemProject and queuingStarted to
	 * their default values (NOT_QUEUED, null, 0)
	 */
	public void unqueue() {
		queueItemId = NOT_QUEUED;
		queueItemProject = null;
		queuingStarted = 0;
	}

	/**
	 * @return True if the resource has been locked by a build, or false
	 * otherwise
	 */
	@Exported
	public boolean isLocked() {
		return build != null;
	}

	/**
	 * @return The build that locked this resource
	 */
	public AbstractBuild<?, ?> getBuild() {
		return build;
	}


	/**
	 * @return The full name of the build that locked this resource
	 */
	@Exported
	public String getBuildName() {
		if (build != null)
			return build.getFullDisplayName();
		else
			return null;
	}

	/**
	 * @param lockedBy "Locks" this resource, by adding a value to this.build
	 */
	public void setBuild(AbstractBuild<?, ?> lockedBy) {
		this.build = lockedBy;
	}

	/**
	 * @return The task in the queue linked to the item with queueItemId
	 */
	public Task getTask() {
		Item item = Queue.getInstance().getItem(queueItemId);
		if (item != null)
			return item.task;
		else
			return null;
	}

	/**
	 * @return The value of queueItemId after validating the queue timeout
	 */
	public int getQueueItemId() {
		this.validateQueuingTimeout();
		return queueItemId;
	}

	/**
	 * @return The name of the project binding this resource
	 * after validating the queue timeout
	 */
	public String getQueueItemProject() {
		this.validateQueuingTimeout();
		return this.queueItemProject;
	}

	/**
	 * @param queueItemId Set the value for queueItemId and set the field
	 * 'queuingStarted' to current time in seconds
	 */
	public void setQueued(int queueItemId) {
		this.queueItemId = queueItemId;
		this.queuingStarted = System.currentTimeMillis() / 1000;
	}

	/**
	 * Set the values for queueItemId and queueProjectName,
	 * and set the field 'queuingStarted' to current time in seconds
	 * @param queueItemId The ID of the queue item that enqueues this resource
	 * @param queueProjectName
	 */
	public void setQueued(int queueItemId, String queueProjectName) {
		this.setQueued(queueItemId);
		this.queueItemProject = queueProjectName;
	}

	/**
	 * Check the amount of seconds passed since this resource has been
	 * queued. If the amount exceeds a given amount - QUEUE_TIMEOUT - the
	 * resource will be dequeued
	 */
	private void validateQueuingTimeout() {
		if (queuingStarted > 0) {
			long now = System.currentTimeMillis() / 1000;
			if (now - queuingStarted > QUEUE_TIMEOUT)
				unqueue();
		}
	}

	/**
	 * Adds a value to the 'reservedForNode' set if the resource is not already reserved for that node
	 * @param nodeName Node name to be added
	 */
	public void setReservedForNode(String nodeName) {
		if(this.reservedForNodesSet.add(nodeName))
			this.reservedForNodes = this.reservedForNodesSet.toString();
	}

	/**
	 * Sets the value for the 'reservedBy' field, thus reserving the resource for an user
	 * @param userName
	 */
	public void setReservedBy(String userName) {
		this.reservedBy = userName;
	}

	/**
	 * Removes a node name from the 'reservedForNodesSet' set
	 * @param nodeName The name of the node that will be removed
	 */
	public void unReserveForNode(String nodeName) {
		if(this.reservedForNodesSet.remove(nodeName))
			this.reservedForNodes = this.reservedForNodesSet.toString();
	}

	/**
	 * Sets the 'reservedForNodes' and 'reservedForNodesSet' to null, thus removing all reservations
	 * set for nodes
	 */
	public void unReserveForAllNodes() {
		this.reservedForNodes    = null;
		this.reservedForNodesSet.clear();
	}

	/**
	 * Updates the 'reservedForNodesSet' based on the 'reservedForNodes' string
	 */
	public void updateReservedForNodesSet() {
		reservedForNodesSet = new WhitespaceSet(reservedForNodes);
	}

	/**
	 * Resets the value for the 'reservedBy' field
	 */
	public void unReserve() {
		this.reservedBy = null;
	}

	/**
	 * Resets the values for the 'reservedForNode', 'reservedBy' and 'build'
	 * fields and dequeues the resource
	 */
	public void reset() {
		this.unReserveForAllNodes();
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

	/**
	 * Extension of class HashSet with overridden toString method in order to easily create a set out of
	 * a string containing tokens separated by whitespace. This is used mainly for reservedForNodes.
	 * The toString method will also return a string of tokens separated by whitespace; if the set is
	 * empty it will return null instead.
	 */
	public static class WhitespaceSet extends HashSet<String> {

		public WhitespaceSet(String str) {
			super(Arrays.asList(str.split("\\s+")));
			/* remove empty element, if it exists */
			this.remove("");
		}

		@Override
		public String toString() {
			if(this.isEmpty())
				return null;

			String result = "";
			for(String s : this)
				result += s + " ";

			return result.trim();
		}
	}

	@Extension
	public static class DescriptorImpl extends Descriptor<LockableResource> {

		@Override
		public String getDisplayName() {
			return "Resource";
		}

	}
}
