/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;

import com.google.common.base.Function;
import com.google.common.base.Predicates;

import hudson.Extension;
import hudson.matrix.MatrixProject;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;

import javax.annotation.Nullable;

import static com.google.common.collect.FluentIterable.from;
import static org.jenkins.plugins.lockableresources.queue.LockableResourcesStructFactory.requiredResources;

@Extension
public class LockableResourcesQueueTaskDispatcher extends QueueTaskDispatcher {

	private static final Logger LOGGER = Logger.getLogger(LockableResourcesQueueTaskDispatcher.class.getName());

	@Override
	public CauseOfBlockage canRun(Queue.Item item) {
		// Skip locking for multiple configuration projects,
		// only the child jobs will actually lock resources.
		if (item.task instanceof MatrixProject)
			return null;

		Job<?, ?> project = Utils.getProject(item);
		if (project == null) {
			return null;
		}

		return from(requiredResources(project))
						.transform(toCauseOfBlockage(item, project))
						.filter(Predicates.<CauseOfBlockage>notNull())
						.first()
						.orNull();
	}

	private static Function<LockableResourcesStruct, CauseOfBlockage> toCauseOfBlockage(final Queue.Item item, final Job<?, ?> project) {
		return new Function<LockableResourcesStruct, CauseOfBlockage>() {

			@Nullable
			@Override
			public CauseOfBlockage apply(@Nullable LockableResourcesStruct resources) {
				return getCauseOfBlockage(item, project, resources);
			}

		};
	}

	@Nullable
	static CauseOfBlockage getCauseOfBlockage(Queue.Item item, Job<?, ?> project, LockableResourcesStruct resources) {
		if (resources == null || (resources.resourceNames.isEmpty() && resources.label.isEmpty())) {
			return null;
		}

		int resourceNumber;
		try {
			resourceNumber = Integer.parseInt(resources.requiredNumber);
		} catch (NumberFormatException e) {
			resourceNumber = 0;
		}

		LOGGER.finest(project.getName() + " trying to get resources with these details: " + resources);

		Map<String, Object> params = Utils.getParams(item);

		List<LockableResource> reservedResources;

		if (resourceNumber > 0 || !resources.label.isEmpty()) {
			reservedResources = LockableResourcesManager.get().queue(resources, item.getId(),
							project.getFullName(), resourceNumber, params);
		} else {
			reservedResources = LockableResourcesManager.get().queue(resources, item.getId(),
							project.getFullName(), params);
		}

		if (reservedResources != null) {
			LOGGER.finest(project.getName() + " reserved resources " + reservedResources);

			return null;
		} else {
			LOGGER.finest(project.getName() + " waiting for resources");

			return new BecauseResourcesLocked(resources);
		}
	}

	private static class BecauseResourcesLocked extends CauseOfBlockage {

		private final LockableResourcesStruct resources;

		BecauseResourcesLocked(LockableResourcesStruct resources) {
			this.resources = resources;
		}

		@Override
		public String getShortDescription() {
			if (resources.label.isEmpty()) {
				return "Waiting for resources " + resources.resourceNames;
			} else {
				return "Waiting for resources with label " + resources.label;
			}
		}

	}

}
