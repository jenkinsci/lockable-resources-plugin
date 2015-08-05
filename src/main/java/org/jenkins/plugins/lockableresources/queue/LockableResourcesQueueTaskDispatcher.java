/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import hudson.Extension;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.AbstractProject;
import hudson.model.Queue;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.model.queue.CauseOfBlockage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;

@Extension
public class LockableResourcesQueueTaskDispatcher extends QueueTaskDispatcher {

	static final Logger LOGGER = Logger.getLogger(LockableResourcesQueueTaskDispatcher.class.getName());

	/**
	 * This method is called when Jenkins checks if this item (build configuration)
	 * can, or cannot, be run on any of the nodes in the network
	 * @param item The item that can or cannot run on any node
	 * @return A cause of blockage if the item cannot be built on any node,
	 * or null if no errors occur
	 */
	@Override
	public CauseOfBlockage canRun(Queue.Item item) {
		// Skip locking for multiple configuration projects,
		// only the child jobs will actually lock resources.
		if (item.task instanceof MatrixProject)
			return null;

		AbstractProject<?, ?> project = Utils.getProject(item);
		if (project == null)
			return null;

		LockableResourcesStruct resources = Utils.requiredResources(project);
		if (resources == null || (resources.required.isEmpty() && resources.label.isEmpty()))
			return null;

		int resourceNumber;
		try {
			resourceNumber = Integer.parseInt(resources.requiredNumber);
		} catch (NumberFormatException e) {
			resourceNumber = 0;
		}

		LOGGER.finest(project.getName() +
			" trying to get resources with these details: " + resources);

		if (resourceNumber > 0 || !resources.label.isEmpty()) {
			Map<String, Object> params = new HashMap<String, Object>();
			if (item.task instanceof MatrixConfiguration) {
			    MatrixConfiguration matrix = (MatrixConfiguration) item.task;
			    params.putAll(matrix.getCombination());
			}

			List<LockableResource> selected = LockableResourcesManager.get()
					.queue( resources,
							item.id,
							project.getFullName(),
							resourceNumber,
							params,
							LOGGER);

			if (selected != null) {
				LOGGER.finest(project.getName() + " reserved resources " + selected);
				return null;
			} else {
				LOGGER.finest(project.getName() + " waiting for resources");
				return new BecauseResourcesLocked(resources);
			}

		} else {
			if (LockableResourcesManager.get().queue(resources.required, item.id)) {
				LOGGER.finest(project.getName() + " reserved resources " + resources.required);
				return null;
			} else {
				LOGGER.finest(project.getName() + " waiting for resources "
					+ resources.required);
				return new BecauseResourcesLocked(resources);
			}
		}
	}

	public static class BecauseResourcesLocked extends CauseOfBlockage {

		private final LockableResourcesStruct rscStruct;

		public BecauseResourcesLocked(LockableResourcesStruct r) {
			this.rscStruct = r;
		}

		@Override
		public String getShortDescription() {
			if (this.rscStruct.label.isEmpty())
				return "Waiting for resources " + rscStruct.required.toString();
			else
				return "Waiting for resources with label " + rscStruct.label;
		}
	}

}
