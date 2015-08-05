/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import hudson.EnvVars;
import hudson.matrix.MatrixConfiguration;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Queue;

import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;

public class Utils {

	/**
	 * @param item The queue item for which to retrieve the project
	 * @return The Jenkins project for the given queue item
	 */
	public static AbstractProject<?, ?> getProject(Queue.Item item) {
		if (item.task instanceof AbstractProject)
			return (AbstractProject<?, ?>) item.task;
		return null;
	}

	/**
	 * @param build The build for which to retrieve the project
	 * @return The Jenkins project that the given build is part of
	 */
	public static AbstractProject<?, ?> getProject(AbstractBuild<?, ?> build) {
		Object p = build.getParent();
		if (p instanceof AbstractProject)
			return (AbstractProject<?, ?>) p;
		return null;
	}

	/**
	 * @param project The project for which a configuration is requested
	 * @return A LockableResourcesStruct variable that contains information
	 * about the resources for the given project
	 */
	public static LockableResourcesStruct requiredResources(
			AbstractProject<?, ?> project) {
		RequiredResourcesProperty property = null;
		EnvVars env = new EnvVars();

		if (project instanceof MatrixConfiguration) {
			env.putAll(((MatrixConfiguration) project).getCombination());
			project = (AbstractProject<?, ?>) project.getParent();
		}

		property = project.getProperty(RequiredResourcesProperty.class);
		if (property != null)
			return new LockableResourcesStruct(property, env);

		return null;
	}
}
