/*
 * The MIT License
 *
 * Copyright (C) 2015 Freescale Semiconductor, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkins.plugins.lockableresources.autogen;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import hudson.slaves.OfflineCause;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.jenkins.plugins.lockableresources.LockableResourcesManager;

@Extension
public class NodeLabelsHandler extends ComputerListener {

	private static transient final Set<NodeWithLabels> nodesWithLabels = new HashSet<NodeWithLabels>();
	private static transient final boolean CONFIGURATION_CHANGE_UPDATE = true;
	private static transient final boolean NEW_NODE_ONLINE_UPDATE = false;

	@Override
	public void onConfigurationChange() {
		for(NodeWithLabels nwl : nodesWithLabels)
			nwl.updateNodeResources(CONFIGURATION_CHANGE_UPDATE);
	}

	@Override
	public void onOnline(Computer c, TaskListener listener)
			throws IOException, InterruptedException {
		nodeCameOnline(c);
	}

	@Override
	public void onOffline(Computer c, OfflineCause oc) {
		nodeWentOffline(c);
	}

	@Override
	public void onTemporarilyOnline(Computer c) {
		nodeCameOnline(c);
	}

	@Override
	public void onTemporarilyOffline(Computer c, OfflineCause cause) {
		nodeWentOffline(c);
	}

	/**
	 * Called when a node comes online by one of the two "onOnline" methods. Will create resources for
	 * the node attached to the given computer if the auto create resources option is active.
	 * @param computer The computer that came online
	 */
	private void nodeCameOnline(Computer computer) {
		Node node = computer.getNode();

		if(node == null)
			return;

		String nodeName = node.getNodeName();
		NodeWithLabels toAdd = new NodeWithLabels(nodeName, node.getLabelString());
		toAdd.updateNodeResources(NEW_NODE_ONLINE_UPDATE);
		nodesWithLabels.add(toAdd);
	}

	/**
	 * Called when a node goes offline by one of the two "onOffline" methods. Will attempt to
	 * remove all resources reserved for the node attached to the the given computer regardless
	 * of whether the option for automatic resource creation is active or not.
	 * @param computer The computer that went offline
	 */
	private void nodeWentOffline(Computer computer) {
		Node node = computer.getNode();

		if(node == null)
			return;

		String nodeName = node.getNodeName();
		NodeWithLabels toRemove = new NodeWithLabels(nodeName, node.getLabelString());
		Utils.removeAutoGenResourcesForNode(node);
		nodesWithLabels.remove(toRemove);
	}

	/**
	 * Class used to monitor the status of node labels. The information stored refers to the node
	 * name and its labels. The only method publicly available (aside from constructors and getters)
	 * is updateNodeResources. The method is used to update the LockableResourcesManager's resources
	 * field, when new resources should be created.
	 */
	private class NodeWithLabels {
		private final transient String nodeName;
		private transient LabelSet nodeLabels;

		public NodeWithLabels(String nodeName) {
			this.nodeName = nodeName.equals("") ? "master" : nodeName;
			nodeLabels = new LabelSet("");
		}

		public NodeWithLabels(String nodeName, LabelSet nodeLabels) {
			this.nodeName = nodeName.equals("") ? "master" : nodeName;
			this.nodeLabels = nodeLabels;
		}

		public NodeWithLabels(String nodeName, String labelString) {
			this.nodeName = nodeName.equals("") ? "master" : nodeName;
			this.nodeLabels = new LabelSet(labelString);
		}

		/**
		 * @return The name of the node that is supervised by this object
		 */
		public String getNode() {
			return this.nodeName;
		}

		/**
		 * @return The labels considered to be the latest. If the node's labels change, this label set will
		 * also be updated
		 */
		public LabelSet getNodeLabels() {
			return this.nodeLabels;
		}

		/**
		 * @param labels A LabelSet containing the new labels
		 * @return True if the labels in 'labels' are the same as the ones in 'nodeLabels',
		 * or false if there is any mismatch
		 */
		private boolean checkNodeLabels(LabelSet labels) {
			return nodeLabels.equals(labels);
		}

		/**
		 * Updates automatically generated resources for this node. The resources are automatically
		 * added to the 'resources' set in LockableResourcesManager.
		 * @param nodeConfigurationChanged If true, all automatically generated resources for this node are
		 * removed before creating new resources. Otherwise, resources are generated for a "new" node, so there
		 * should not be any resources to remove (note that this method will not check if there are any resources
		 * to remove in that case).
		 * <p>
		 * A "new" node is either a node that is brought online after being temporarily offline,
		 * or permanently offline (its client program had stopped)
		 */
		public void updateNodeResources(boolean nodeConfigurationChanged) {
			Node node = Utils.getNodeWithName(nodeName);
			LabelSet newLabels = new LabelSet(node.getLabelString());

			if(LockableResourcesManager.get().getAutoCreateResources()) {
				if(! nodeConfigurationChanged)
					Utils.autoGenerateNodeResources(node, newLabels);

				else if(! checkNodeLabels(newLabels)) {
					this.nodeLabels = newLabels;
					Utils.removeAutoGenResourcesForNode(node);
					Utils.autoGenerateNodeResources(node, newLabels);
				}
			}
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((nodeName == null) ? 0 : nodeName.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == null)
				return false;

			if (this.getClass() != obj.getClass())
				return false;

			final NodeWithLabels other = (NodeWithLabels) obj;
			if ((this.nodeName == null) ? (other.nodeName != null) : !this.nodeName.equals(other.nodeName))
				return false;

			return true;
		}
	}
}
