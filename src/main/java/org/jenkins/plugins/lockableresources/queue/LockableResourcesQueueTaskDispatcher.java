/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                       *
 *                                                                           *
 * Dynamic resources management by Darius Mihai (mihai_darius22@yahoo.com)   *
 * Copyright (C) 2015 Freescale Semiconductor, Inc.                          *
 *                                                                           *
 * This file is part of the Jenkins Lockable Resources Plugin and is         *
 * published under the MIT license.                                          *
 *                                                                           *
 * See the "LICENSE.txt" file for more information.                          *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
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
import java.util.Set;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.dynamicres.DynamicInfoData;
import org.jenkins.plugins.lockableresources.dynamicres.DynamicResourcesManager;
import org.jenkins.plugins.lockableresources.dynamicres.DynamicResourcesProperty;
import org.jenkins.plugins.lockableresources.dynamicres.DynamicUtils;

@Extension
public class LockableResourcesQueueTaskDispatcher extends QueueTaskDispatcher {

	static final Logger LOGGER = Logger
			.getLogger(LockableResourcesQueueTaskDispatcher.class.getName());

	@Override
	public CauseOfBlockage canRun(Queue.Item item) {
		// Skip locking for multiple configuration projects,
		// only the child jobs will actually lock resources.
		if (item.task instanceof MatrixProject)
			return null;

		AbstractProject<?, ?> project = Utils.getProject(item);
		if (project == null)
			return null;

		/* get the required lockable resources */
		LockableResourcesStruct resources = Utils.requiredResources(project);

		/* check dynamic resources properties */
		DynamicResourcesProperty dynamicProperty = DynamicUtils.getDynamicProperty(project);

		Set<Map<?, ?>> requiredDynamicResources = null;
		/* if the build should consume dynamic resources, get a dynamic resource configuration that
		 * it should use
		 */
		if (dynamicProperty != null ) {
			DynamicInfoData data = new DynamicInfoData(dynamicProperty, item);
			DynamicUtils.updateJobDynamicInfoIfRequired(dynamicProperty, data);

			if(dynamicProperty.getConsumeDynamicResources()) {
				requiredDynamicResources = DynamicUtils.getJobDynamicInfoConsume(dynamicProperty, data);

				if (requiredDynamicResources != null) {
					LOGGER.finest("Requesting dynamic resources: " + requiredDynamicResources);

					/* if the build should consume resources, check if the resources for
					 * the current configuration are available; if not, interrupt
					 */
					if(!DynamicResourcesManager.checkDynamicResources(requiredDynamicResources))
						return new BecauseNoDynamicResources(requiredDynamicResources);
				}
			}
		}

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
			Map<String, Object> params = new HashMap<String, Object>();
			if (item.task instanceof MatrixConfiguration) {
			    MatrixConfiguration matrix = (MatrixConfiguration) item.task;
			    params.putAll(matrix.getCombination());
			}

			List<LockableResource> selected = LockableResourcesManager.get().queue(
					resources,
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

	public static class BecauseNoDynamicResources extends CauseOfBlockage {

		private final Set<Map<?, ?>> dynamicResourcesRequired;

		public BecauseNoDynamicResources(Set<Map<?, ?>> dynamicResourcesRequired) {
			this.dynamicResourcesRequired = dynamicResourcesRequired;
		}

		@Override
		public String getShortDescription() {
			return "Required dynamic resource for config: " + dynamicResourcesRequired.toString();
		}
	}
}
