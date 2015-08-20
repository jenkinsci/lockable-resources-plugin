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
import hudson.matrix.MatrixBuild;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.listeners.RunListener;
import hudson.model.ParametersAction;
import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;
import org.jenkins.plugins.lockableresources.dynamicres.DynamicInfoData;
import org.jenkins.plugins.lockableresources.dynamicres.DynamicResourcesManager;
import org.jenkins.plugins.lockableresources.dynamicres.DynamicResourcesProperty;
import org.jenkins.plugins.lockableresources.dynamicres.DynamicUtils;

@Extension
public class LockRunListener extends RunListener<AbstractBuild<?, ?>> {

	static final String LOG_PREFIX = "[lockable-resources]";
	static final Logger LOGGER = Logger.getLogger(LockRunListener.class
			.getName());

	@Override
	public void onStarted(AbstractBuild<?, ?> build, TaskListener listener) {
		// Skip locking for multiple configuration projects,
		// only the child jobs will actually lock resources.
		if (build instanceof MatrixBuild)
			return;

		AbstractProject<?, ?> proj = Utils.getProject(build);
		List<LockableResource> required = new ArrayList<LockableResource>();
		if (proj != null) {
			LockableResourcesStruct resources = Utils.requiredResources(proj);
			if (resources != null) {
				if (resources.requiredNumber != null || !resources.label.isEmpty()) {
					required = LockableResourcesManager.get().
						getResourcesFromProject(proj.getFullName());
				} else {
					required = resources.required;
				}
				if (LockableResourcesManager.get().lock(required, build)) {
					build.addAction(LockedResourcesBuildAction
							.fromResources(required));
					listener.getLogger().printf("%s acquired lock on %s\n",
							LOG_PREFIX, required);
					LOGGER.fine(build.getFullDisplayName()
							+ " acquired lock on " + required);
					if (resources.requiredVar != null) {
						List<ParameterValue> params = new ArrayList<ParameterValue>();
						params.add(new StringParameterValue(
							resources.requiredVar,
							required.toString().replaceAll("[\\]\\[]", "")));
						build.addAction(new ParametersAction(params));
					}
				} else {
					listener.getLogger().printf("%s failed to lock %s\n",
							LOG_PREFIX, required);
					LOGGER.fine(build.getFullDisplayName() + " failed to lock "
							+ required);
				}
			}

			/* check if the build should consume dynamic resources; if so, consume a
			 * dynamic resource for the current configuration.
			 */
			DynamicResourcesProperty dynamicProperty = DynamicUtils.getDynamicProperty(proj);
			if (dynamicProperty != null && dynamicProperty.getConsumeDynamicResources()) {
				DynamicInfoData data = new DynamicInfoData(dynamicProperty, build);

				Set<Map<?, ?>> configs = DynamicUtils.getJobDynamicInfoConsume(dynamicProperty, data);

				if (DynamicResourcesManager.consumeDynamicResources(configs))
					listener.getLogger().println("Consumed resource for: " + configs);
			}
		}
	}

	@Override
	public void onCompleted(AbstractBuild<?, ?> build, TaskListener listener) {
		// Skip unlocking for multiple configuration projects,
		// only the child jobs will actually unlock resources.
		if (build instanceof MatrixBuild)
			return;

		// obviously project name cannot be obtained here
		List<LockableResource> required = LockableResourcesManager.get()
				.getResourcesFromBuild(build);

		/* check if the build should create dynamic resources; if so, create a
		 * resource for the each possible configuration
		 */
		AbstractProject<?, ?> proj = Utils.getProject(build);
		if (proj != null) {
			DynamicResourcesProperty dynamicProperty = DynamicUtils.getDynamicProperty(proj);
			if (dynamicProperty != null && dynamicProperty.getCreateDynamicResources()) {
				DynamicInfoData data = new DynamicInfoData(dynamicProperty, build);

				Set<Map<?, ?>> configs = DynamicUtils.getJobDynamicInfoCreate(dynamicProperty, data);

				if (DynamicResourcesManager.createDynamicResources(configs))
					listener.getLogger().println("Created resource for: " + configs);
			}
		}

		if (required.size() > 0) {
			LockableResourcesManager.get().unlock(required, build);
			listener.getLogger().printf("%s released lock on %s\n",
					LOG_PREFIX, required);
			LOGGER.fine(build.getFullDisplayName() + " released lock on "
					+ required);
		}

		DynamicUtils.buildEnded(build);
	}

	@Override
	public void onDeleted(AbstractBuild<?, ?> build) {
		// Skip unlocking for multiple configuration projects,
		// only the child jobs will actually unlock resources.
		if (build instanceof MatrixBuild)
			return;

		List<LockableResource> required = LockableResourcesManager.get()
				.getResourcesFromBuild(build);
		if (required.size() > 0) {
			LockableResourcesManager.get().unlock(required, build);
			LOGGER.fine(build.getFullDisplayName() + " released lock on "
					+ required);
		}

		DynamicUtils.buildEnded(build);
	}
}
