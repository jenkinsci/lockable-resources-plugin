/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import hudson.matrix.MatrixConfiguration;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Queue;

import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;

public class Utils {

	public static AbstractProject<?, ?> getProjectOfQueueItem(Queue.Item item) {
		if (item.task instanceof AbstractProject) {
			AbstractProject<?, ?> proj = (AbstractProject<?, ?>) item.task;
			if (proj instanceof MatrixConfiguration) {
				proj = (AbstractProject<?, ?>) ((MatrixConfiguration) proj)
						.getParent();
			}
			return proj;
		}
		return null;
	}

	public static AbstractProject<?, ?> getProjectOfBuild(AbstractBuild<?, ?> build) {
		Object p = build.getParent();
		if (p instanceof AbstractProject) {
			AbstractProject<?, ?> proj = (AbstractProject<?, ?>) p;
			if (proj instanceof MatrixConfiguration) {
				proj = (AbstractProject<?, ?>) ((MatrixConfiguration) proj)
						.getParent();
			}
			return proj;
		}
		return null;
	}

	public static LockableResourcesStruct getResourcesConfigurationForProject(
			AbstractProject<?, ?> project) {
		RequiredResourcesProperty property = project
				.getProperty(RequiredResourcesProperty.class);
		if (property != null) {
			return new LockableResourcesStruct(property);
		}
		return null;
	}
}
