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
import hudson.model.AbstractProject;
import hudson.model.Queue;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.model.queue.CauseOfBlockage;

import java.util.List;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.LockableResource;

@Extension
public class LockableResourcesQueueTaskDispatcher extends QueueTaskDispatcher {

	static final Logger LOGGER = Logger
			.getLogger(LockableResourcesQueueTaskDispatcher.class.getName());

	@Override
	public CauseOfBlockage canRun(Queue.Item item) {

		AbstractProject<?, ?> project = Utils.getProjectOfQueueItem(item);
		if (project == null)
			return null;

		LockableResourcesStruct resources = Utils.getResourcesConfigurationForProject(project);
		if (resources == null ||
			(resources.required.isEmpty() && resources.label.isEmpty())) {
			return null;
		}

		int resourceNumber;
		try {
			resourceNumber = Integer.parseInt(resources.requiredNumber);
		} catch (NumberFormatException e) {
			resourceNumber = 0;
		}

		LOGGER.finest(project.getName() +
			" trying to get resources with these details: " + resources);

		if (resourceNumber > 0 || !resources.label.isEmpty()) {

			List<LockableResource> selected = LockableResourcesManager.get().queue(
					resources,
					item.id,
					project.getFullName(),
					resourceNumber,
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
