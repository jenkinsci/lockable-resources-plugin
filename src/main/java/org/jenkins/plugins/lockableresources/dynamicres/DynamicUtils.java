/*
 * The MIT License
 *
 * Dynamic resources management by Darius Mihai (mihai_darius22@yahoo.com)
 * Copyright (C) 2015 Freescale Semiconductor, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkins.plugins.lockableresources.dynamicres;

import hudson.matrix.Combination;
import hudson.matrix.MatrixConfiguration;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Queue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class DynamicUtils {
	/**
	 * @param project The project whose DynamicResourcesProperty is required
	 * @return The DynamicResourcesProperty attached to this project
	 */
	public static DynamicResourcesProperty getDynamicProperty(AbstractProject<?, ?> project) {
		if(project instanceof MatrixConfiguration)
			project = (AbstractProject<?, ?>) project.getParent();

		return project.getProperty(DynamicResourcesProperty.class);
	}

	/**
	 * @param item The item whose last build is required
	 * @return The last build found for item.task; can be a build with a matrix
	 * configuration - use only to get the matrix configuration, as the returned
	 * value might be an older build with different environment variables
	 */
	public static AbstractBuild<?, ?> getBuild(Queue.Item item) {
		if (item.task instanceof AbstractProject) {
			AbstractProject<?, ?> proj = (AbstractProject<?, ?>) item.task;
			return proj.getLastBuild();
		}
		return null;
	}

	/**
	 * @param item The item whose build variables are required
	 * @return The build variables associated with the last build of the given Queue.Item's task.
	 * If the task associated with the item is a matrix configuration, the build variables of the master job
	 * are returned instead.
	 */
	public static Map<String, String> getBuildVariables(Queue.Item item) {
		if (item.task instanceof AbstractProject) {
			AbstractProject<?, ?> proj = (AbstractProject<?, ?>) item.task;
			if(proj instanceof MatrixConfiguration)
				return ((AbstractProject<?, ?>) proj.getParent()).getLastBuild().getBuildVariables();
			else
				return proj.getLastBuild().getBuildVariables();
		}

		return null;
	}

	/**
	 * @param build The build whose matrix configuration is required
	 * @return A set corresponding to the matrix configuration of the project that the given build is part of
	 */
	public static Set<Entry<String, String>> getProjectConfiguration(AbstractBuild<?, ?> build) {
		MatrixConfiguration proj = (MatrixConfiguration) build.getProject();
		Combination projectComb = proj.getCombination();

		return projectComb.entrySet();
	}

	/**
	 * @param build The build whose configuration is required
	 * @param injectedId The name of the variable used as a token (can be received from an upstream build).
	 * Must be in characteristicEnvVars
	 * @param injectedValue The value of the injected variable. null means value is read from build
	 * @param ignoreAxis Names of axis in the current matrix configuration that will be ignored
	 * when generating the dynamic resource configuration
	 * @param generatedForJob The name of the job (only one) that should consume this resource
	 * @return A map containing all matrix configuration axis and their respective values
	 * (except the axis specified in ignoreAxis), a pair for injectedId-injectedValue,
	 * and a pair for "RESERVED_FOR_JOB"-generatedForJob
	 */
	public static synchronized Map<?, ?> getDynamicResConfig(AbstractBuild<?, ?> build,
															 String injectedId,
															 String injectedValue,
															 String ignoreAxis,
															 String generatedForJob) {
		if (build == null)
			return null;

		String jobName;
		Map<String, String> configuration = new HashMap<String, String>();

		jobName = build.getCharacteristicEnvVars().get("JOB_NAME");
		if (jobName == null)
			return null;

		/* Check the value for the token; if the configuration is requested
		 * without sending the value as a parameter, it will extracted from the build's variables.
		 */
		if (injectedValue == null)
			injectedValue = build.getBuildVariables().get(injectedId);
		configuration.put(injectedId, injectedValue);

		/* The JOB_NAME parameter should look like "master_job_name[/axis1=value1,]
		 * [axis2=value2]...[axisN=valueN] - values in brackets are optional. Check if there are
		 * axis_names - values pairs, and add them to the configuration
		 */
		String[] splitName = jobName.split("/");

		/* add the name of the build that should consume the resource to the configuration.
		 * If the received parameter is null, use the master_job_name instead.
		 */
		if (generatedForJob == null)
			generatedForJob = splitName[0];

		configuration.put("RESERVED_FOR_JOB", generatedForJob);

		Set<String> ignoredAxis = new HashSet<String>();
		if (ignoreAxis != null)
			ignoredAxis.addAll(Arrays.asList(ignoreAxis.split("\\s+")));

		if (build.getProject() instanceof MatrixConfiguration) {
			for(Map.Entry mapE : getProjectConfiguration(build))
				if(! ignoredAxis.contains(mapE.getKey().toString()))
					configuration.put(mapE.getKey().toString(), mapE.getValue().toString());
		}

		/* the size should be at least 2, since one of the elements is the injectedId */
		return configuration.size() < 2 ? null : configuration;
	}

	/**
	 * @param item The queue item whose configuration is required
	 * @param injectedId The name of the variable used as a token (can be received from an upstream build)
	 * Must be in characteristicEnvVars
	 * @param ignoreAxis Axis names of the current matrix configuration that
	 * will be ignored when generating resources
	 * @param generatedForJob The name of the job (only one) that should consume this resource
	 * @return A map containing all matrix configuration axis and their respective values
	 * (except the axis in ignoredAxis), a pair for injectedId-injectedValue,
	 * and a pair for "RESERVED_FOR_JOB"-generatedForJob
	 */
	public static synchronized Map<?, ?> getDynamicResConfig(Queue.Item item,
															 String injectedId,
															 String ignoreAxis,
															 String generatedForJob) {
		AbstractBuild<?, ?> currentBuild = getBuild(item);
		String injectedValue = getBuildVariables(item).get(injectedId);

		return getDynamicResConfig( currentBuild,
									injectedId,
									injectedValue,
									ignoreAxis,
									generatedForJob);
	}

}
