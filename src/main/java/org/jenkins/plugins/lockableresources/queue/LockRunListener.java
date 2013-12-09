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
		if (proj != null) {
			List<LockableResource> resources = Utils.requiredResources(proj);
			if (resources != null) {
				if (LockableResourcesManager.get().lock(resources, build)) {
					build.addAction(LockedResourcesBuildAction
							.fromResources(resources));
					listener.getLogger().printf("%s acquired lock on %s\n",
							LOG_PREFIX, resources);
					LOGGER.fine(build.getFullDisplayName()
							+ " acquired lock on " + resources);
				} else {
					listener.getLogger().printf("%s failed to lock %s\n",
							LOG_PREFIX, resources);
					LOGGER.fine(build.getFullDisplayName() + " failed to lock "
							+ resources);
				}
			}
		}
	}

	@Override
	public void onCompleted(AbstractBuild<?, ?> build, TaskListener listener) {
		AbstractProject<?, ?> proj = Utils.getProject(build);
		if (proj != null) {
			List<LockableResource> resources = Utils.requiredResources(proj);
			if (resources != null) {
				LockableResourcesManager.get().unlock(resources, build);
				listener.getLogger().printf("%s released lock on %s\n",
						LOG_PREFIX, resources);
				LOGGER.fine(build.getFullDisplayName() + " released lock on "
						+ resources);
			}
		}
	}

	@Override
	public void onDeleted(AbstractBuild<?, ?> build) {
		AbstractProject<?, ?> proj = Utils.getProject(build);
		if (proj != null) {
			List<LockableResource> resources = Utils.requiredResources(proj);
			if (resources != null) {
				LockableResourcesManager.get().unlock(resources, build);
				LOGGER.fine(build.getFullDisplayName() + " released lock on "
						+ resources);
			}
		}
	}

}
