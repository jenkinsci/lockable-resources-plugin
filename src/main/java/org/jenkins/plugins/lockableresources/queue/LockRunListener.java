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
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.listeners.RunListener;
import hudson.model.ParametersAction;
import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;

@Extension
public class LockRunListener extends RunListener<AbstractBuild<?, ?>> {

	static final String LOG_PREFIX = "[lockable-resources]";
	static final Logger LOGGER = Logger.getLogger(LockRunListener.class
			.getName());

	/**
	 * Method called at the start of the task. Displays information about the status of the
	 * resources used by the task. The call happens `after` the start of the
	 * task, so failing to obtain a lock on the resources does not influence the
	 * way the task runs, but will cause problems for the overall project,
	 * as the resource might be stuck, and the execution will start even though it
	 * most likely shouldn't
	 * @param build The build that started - can be a matrix configuration
	 * @param listener Task listener - used for logging data
	 */
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
		}
	}

	/**
	 * Called after a task has completed, in order to release the lock on any
	 * resources that were locked by the current build
	 * @param build The build that finished
	 * @param listener Task listener - used for logging data
	 */
	@Override
	public void onCompleted(AbstractBuild<?, ?> build, TaskListener listener) {
		// Skip unlocking for multiple configuration projects,
		// only the child jobs will actually unlock resources.
		if (build instanceof MatrixBuild)
			return;

		// obviously project name cannot be obtained here
		List<LockableResource> required = LockableResourcesManager.get()
				.getResourcesFromBuild(build);
		if (required.size() > 0) {
			LockableResourcesManager.get().unlock(required, build);
			listener.getLogger().printf("%s released lock on %s\n",
					LOG_PREFIX, required);
			LOGGER.fine(build.getFullDisplayName() + " released lock on "
					+ required);
		}

	}

	/**
	 * Called when deleting a task, in order to release the lock
	 * on the resources locked by the current build
	 * @param build The deleted build
	 */
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
	}

}
