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
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;

public final class Utils {
private Utils() {

}
	public static Job<?, ?> getProject(Queue.Item item) {
		if (item.task instanceof Job)
			return (Job<?, ?>) item.task;
		return null;
	}

	public static Job<?, ?> getProject(Run<?, ?> build) {
		Object p = build.getParent();
		return (Job<?, ?>) p;
	}

	public static LockableResourcesStruct requiredResources(
			Job<?, ?> project) {
		EnvVars env = new EnvVars();

		if (project instanceof MatrixConfiguration) {
			env.putAll(((MatrixConfiguration) project).getCombination());
			project = (Job<?, ?>) project.getParent();
		}

		RequiredResourcesProperty property = project.getProperty(RequiredResourcesProperty.class);
		if (property != null)
			return new LockableResourcesStruct(property, env);

		return null;
	}
}
