/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, Aki Asikainen. All rights reserved.             *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jenkins.plugins.lockableresources.RequiredResources;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;

import hudson.EnvVars;
import hudson.matrix.MatrixConfiguration;
import hudson.model.Job;

public class LockableResourcesStructFactory {

	public static List<LockableResourcesStruct> requiredResources(Job<?, ?> project) {
		EnvVars env = new EnvVars();

		if (project instanceof MatrixConfiguration) {
			env.putAll(((MatrixConfiguration) project).getCombination());

			project = (Job<?, ?>) project.getParent();
		}

		RequiredResourcesProperty property = project.getProperty(RequiredResourcesProperty.class);

		if (property != null) {
			List<LockableResourcesStruct> lockableResourcesStructs = new ArrayList<>();

			for (RequiredResources requiredResources : property.getRequiredResourcesList()) {
				lockableResourcesStructs.add(new LockableResourcesStruct(requiredResources, env));
			}

			return lockableResourcesStructs;
		}

		return Collections.emptyList();
	}

}
