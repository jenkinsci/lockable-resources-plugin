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
import hudson.matrix.MatrixBuild;
import hudson.model.AbstractBuild;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.model.StringParameterValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;
import org.jenkins.plugins.lockableresources.actions.ResourceVariableNameAction;

@Extension
public class LockRunListener extends RunListener<Run<?, ?>> {

	static final String LOG_PREFIX = "[lockable-resources]";
	static final Logger LOGGER = Logger.getLogger(LockRunListener.class
			.getName());

	@Override
	public void onStarted(Run<?, ?> build, TaskListener listener) {
		// Skip locking for multiple configuration projects,
		// only the child jobs will actually lock resources.
		if (build instanceof MatrixBuild)
			return;

		if (build instanceof AbstractBuild) {
			Job<?, ?> proj = Utils.getProject(build);
			Set<LockableResource> required = new HashSet<LockableResource>();
			if (proj != null) {
				LockableResourcesStruct resources = Utils.requiredResources(proj);

				if (resources != null) {
					if (resources.requiredNumber != null || !resources.label.isEmpty() || resources.getResourceMatchScript() != null) {
						required.addAll(LockableResourcesManager.get().
								getResourcesFromProject(proj.getFullName()));
					} else {
						required.addAll(resources.required);
					}

					if (LockableResourcesManager.get().lock(required, build, null)) {
						build.addAction(LockedResourcesBuildAction
								.fromResources(required));
						listener.getLogger().printf("%s acquired lock on %s%n",
								LOG_PREFIX, required);
						LOGGER.fine(build.getFullDisplayName()
								+ " acquired lock on " + required);
						if (resources.requiredVar != null) {
							build.addAction(new ResourceVariableNameAction(new StringParameterValue(
									resources.requiredVar,
									required.toString().replaceAll("[\\]\\[]", ""))));
						}
					} else {
						listener.getLogger().printf("%s failed to lock %s%n",
								LOG_PREFIX, required);
						LOGGER.fine(build.getFullDisplayName() + " failed to lock "
								+ required);
					}
				}
			}
		}

		return;
	}

	@Override
	public void onCompleted(Run<?, ?> build, TaskListener listener) {
		// Skip unlocking for multiple configuration projects,
		// only the child jobs will actually unlock resources.
		if (build instanceof MatrixBuild)
			return;

		// obviously project name cannot be obtained here
		List<LockableResource> required = LockableResourcesManager.get()
				.getResourcesFromBuild(build);
		if (required.size() > 0) {
			LockableResourcesManager.get().unlock(required, build);
			listener.getLogger().printf("%s released lock on %s%n",
					LOG_PREFIX, required);
			LOGGER.fine(build.getFullDisplayName() + " released lock on "
					+ required);
		}

	}

	@Override
	public void onDeleted(Run<?, ?> build) {
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
	}

}
