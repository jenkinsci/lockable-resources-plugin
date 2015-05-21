/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013-2015, 6WIND S.A.                                 *
 *                          SAP SE                                     *
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

import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.LockableResource;

@Extension
public class LockableResourcesQueueTaskDispatcher extends QueueTaskDispatcher {

	static final Logger LOGGER = Logger
			.getLogger(LockableResourcesQueueTaskDispatcher.class.getName());

	@Override
	public CauseOfBlockage canRun(Queue.Item item) {
		try {
			AbstractProject<?, ?> project = Utils.getProject(item);
			if (project == null)
				return null;

			LockableResourcesStruct resources = Utils.requiredResources(project);
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

			LOGGER.log(Level.FINEST, "{0} trying to get resources with these details: {1}",
					new Object[]{project.getName(), resources});

			if (resourceNumber > 0 || !resources.label.isEmpty()) {

				Collection<LockableResource> selected = LockableResourcesManager.get().queue(
						resources,
						item.id,
						project.getFullName(),
						resourceNumber,
						LOGGER);

				if (selected != null) {
					LOGGER.log(Level.FINEST, "{0} reserved resources {1}",
							new Object[]{project.getName(), selected});
					return null;
				} else {
					LOGGER.log(Level.FINEST, "{0} waiting for resources", project.getName());
					return new BecauseResourcesLocked(resources);
				}

			} else {
				if (LockableResourcesManager.get().queue(resources.required, item.id)) {
					LOGGER.log(Level.FINEST, "{0} reserved resources {1}",
							new Object[]{project.getName(), resources.required});
					return null;
				} else {
					LOGGER.log(Level.FINEST, "{0} waiting for resources {1}",
							new Object[]{project.getName(), resources.required});
					return new BecauseResourcesLocked(resources);
				}
			}
		}
		catch ( RuntimeException ex ) {
			LOGGER.log(Level.SEVERE, "Unexpected exception!", ex);
			throw ex;
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
