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

	@Override
	public void onStarted(AbstractBuild<?, ?> build, TaskListener listener) {
		AbstractProject<?, ?> proj = Utils.getProject(build);
		List<LockableResource> required = new ArrayList<LockableResource>();
		if (proj != null) {
			LockableResourcesStruct resources = Utils.requiredResources(proj);
			if (resources != null) {
				if (resources.requiredNumber != null) {
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

	@Override
	public void onCompleted(AbstractBuild<?, ?> build, TaskListener listener) {
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

	@Override
	public void onDeleted(AbstractBuild<?, ?> build) {
		List<LockableResource> required = LockableResourcesManager.get()
				.getResourcesFromBuild(build);
		if (required.size() > 0) {
			LockableResourcesManager.get().unlock(required, build);
			LOGGER.fine(build.getFullDisplayName() + " released lock on "
					+ required);
		}
	}

}
