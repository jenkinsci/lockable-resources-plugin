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
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;
import org.jenkins.plugins.lockableresources.actions.ResourceVariableNameAction;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

import hudson.Extension;
import hudson.matrix.MatrixBuild;
import hudson.model.AbstractBuild;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

import javax.annotation.Nonnull;

@Extension
public class LockRunListener extends RunListener<Run<?, ?>> {

	private static final String LOG_PREFIX = "[lockable-resources]";

	private static final Logger LOGGER = Logger.getLogger(LockRunListener.class.getName());

	@Override
	public void onStarted(Run<?, ?> build, TaskListener listener) {
		if (checkBuildType(build)) {
			return;
		}

		if (build instanceof AbstractBuild) {
			Job<?, ?> project = Utils.getProject(build);

			Multimap<LockableResourcesStruct, LockableResource> requiredResourcesMap = getRequiredResources(project);

			List<LockableResource> lockedResources = Lists.newArrayList();

			for (LockableResourcesStruct resources : requiredResourcesMap.keySet()) {
				List<LockableResource> requiredResources = Lists.newArrayList(requiredResourcesMap.get(resources));

				boolean locked = LockableResourcesManager.get().lock(requiredResources, build, null);

				if (locked) {
					lockedResources.addAll(requiredResources);

					listener.getLogger().printf("%s acquired lock on %s%n", LOG_PREFIX, requiredResources);

					LOGGER.fine(build.getFullDisplayName() + " acquired lock on " + requiredResources);

					if (resources.requiredVar != null) {
						build.addAction(new ResourceVariableNameAction(new StringParameterValue(
										resources.requiredVar,
										requiredResources.toString().replaceAll("[\\]\\[]", ""))));
					}
				} else {
					listener.getLogger().printf("%s failed to lock %s%n", LOG_PREFIX, requiredResources);

					LOGGER.fine(build.getFullDisplayName() + " failed to lock " + requiredResources);
				}
			}

			build.addAction(LockedResourcesBuildAction.fromResources(lockedResources));
		}
	}

	@Override
	public void onCompleted(Run<?, ?> build, @Nonnull TaskListener listener) {
		if (checkBuildType(build)) {
			return;
		}

		List<LockableResource> requiredResources = LockableResourcesManager.get().getResourcesFromBuild(build);

		if (requiredResources.size() > 0) {
			LockableResourcesManager.get().unlock(requiredResources, build, null);

			listener.getLogger().printf("%s released lock on %s%n", LOG_PREFIX, requiredResources);

			LOGGER.fine(build.getFullDisplayName() + " released lock on " + requiredResources);
		}
	}

	@Override
	public void onDeleted(Run<?, ?> build) {
		if (checkBuildType(build)) {
			return;
		}

		List<LockableResource> requiredResources = LockableResourcesManager.get().getResourcesFromBuild(build);

		if (requiredResources.size() > 0) {
			LockableResourcesManager.get().unlock(requiredResources, build, null);

			LOGGER.fine(build.getFullDisplayName() + " released lock on " + requiredResources);
		}
	}

	private boolean checkBuildType(Run<?, ?> build) {
		// Skip unlocking for multiple configuration projects,
		// only the child jobs will actually unlock resources.
		return build instanceof MatrixBuild;
	}

	private Multimap<LockableResourcesStruct, LockableResource> getRequiredResources(Job<?, ?> project) {
		LockableResourcesManager lockableResourcesManager = LockableResourcesManager.get();
		Map<String, Object> params = Utils.getParams(project);

		Multimap<LockableResourcesStruct, LockableResource> requiredResourcesMap = ArrayListMultimap.create();

		for (LockableResourcesStruct resources : LockableResourcesStructFactory.requiredResources(project)) {
			if (resources.requiredNumber != null || !resources.label.isEmpty()) {
				requiredResourcesMap.putAll(resources, lockableResourcesManager.getResourcesFromProject(project.getFullName(), resources.label, params));
			} else {
				requiredResourcesMap.putAll(resources, lockableResourcesManager.getResourcesWithNames(resources.resourceNames));
			}
		}

		return requiredResourcesMap;
	}

}
