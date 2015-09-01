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

	/** A string used to identify the token in dynamic resources configurations */
	public final static String DYNAMICRES_TOKENID = "DYNAMIC_RESOURCES_TOKEN";
	/** A map used to identify tokens used by projects for dynamic resource configurations */
	private final static transient Map<String, String> tokens = new HashMap<String, String>();

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
	 * @param item An item whose master job name is required
	 * @return The name of the task, as defined in the Jenkins configuration page
	 */
	public static String getJobName(Queue.Item item) {
		return ((AbstractProject<?, ?>) item.task).getFullName().split("/")[0];
	}

	/**
	 * @param build A build whose master job name is required
	 * @return The name of the project, as defined in the Jenkins configuration page
	 */
	public static String getJobName(AbstractBuild<?, ?> build) {
		return build.getProject().getFullName().split("/")[0];
	}

	/**
	 * @param item An item whose task (project/job) name is used to get an "unique" name
	 * @return An unique name for the item. Is formed by appending " - buildNumber" at the
	 * end of the full name of the build (name + matrix configuration). (Note: buildNumber is
	 * the actual value of project's next build number. If the project is a MatrixConfiguration,
	 * the master job's build number is used instead).
	 */
	public static String getUniqueName(Queue.Item item) {
		AbstractProject<?, ?> proj = (AbstractProject<?, ?>) item.task;

		if(proj instanceof MatrixConfiguration)
			return proj.getFullName() + " - " + ((AbstractProject<?, ?>) proj.getParent()).getNextBuildNumber();

		return proj.getFullName() + " - " + proj.getNextBuildNumber();
	}

	/**
	 * @param build A build whose project (job) name is used to get an "unique" name
	 * @return An unique name for the build. Is formed by appending " - buildNumber" at the
	 * end of the full name of the build (name + matrix configuration). (Note: buildNumber is
	 * the actual value of the project's next build number. If the project is a MatrixConfiguration,
	 * the master job's build number is used instead).
	 */
	public static String getUniqueName(AbstractBuild<?, ?> build) {
		AbstractProject<?, ?> proj = build.getProject();

		if(proj instanceof MatrixConfiguration)
			return proj.getFullName() + " - " + ((AbstractProject<?, ?>) proj.getParent()).getNextBuildNumber();

		return proj.getFullName() + " - " + proj.getNextBuildNumber();
	}

	/**
	 * Used to create an id for the project, based on the name of its master job and the next build number.
	 * @param project A project whose master job is used
	 * @return An (unique) identifier
	 */
	private static String getCurrentRunTokenID(AbstractProject<?, ?> project) {
		AbstractProject<?, ?> proj = project;

		if(proj instanceof MatrixConfiguration)
			proj = (AbstractProject<?, ?>) proj.getParent();

		return proj.getFullName() + " - " + proj.getNextBuildNumber();
	}

	/**
	 * Used to create an id for the project, based on the name of its master job and the next build number.
	 * <p>
	 * As the project has not yet started, the next build number + 1 is used instead.
	 * @param project A project whose master job is used
	 * @return An (unique) identifier
	 */
	private static String getFutureRunTokenID(AbstractProject<?, ?> project) {
		AbstractProject<?, ?> proj = project;

		if(proj instanceof MatrixConfiguration)
			proj = (AbstractProject<?, ?>) proj.getParent();

		return proj.getFullName() + " - " + (proj.getNextBuildNumber() + 1);
	}

	/**
	 * @param build The build whose matrix configuration is required
	 * @return The matrix configuration for the project that the given build is part of
	 */
	public static Set<Entry<String, String>> getProjectConfiguration(AbstractBuild<?, ?> build) {
		if(! (build.getProject() instanceof MatrixConfiguration))
			return null;

		MatrixConfiguration proj = (MatrixConfiguration) build.getProject();
		Combination projectComb = proj.getCombination();

		return projectComb.entrySet();
	}

	/**
	 * @param item The item whose matrix configuration will be retrieved (using item.task)
	 * @return The matrix configuration for the given item's task
	 */
	public static Set<Entry<String, String>> getProjectConfiguration(Queue.Item item) {
		if(! (item.task instanceof MatrixConfiguration))
			return null;

		MatrixConfiguration proj = (MatrixConfiguration) item.task;
		Combination projectComb = proj.getCombination();

		return projectComb.entrySet();
	}

	/**
	 * Method used to obtain a dynamic resource configuration for a job. The configuration
	 * is based on the matrix configuration, ignoring axis marked as such, and the token for the job.
	 * This token is considered to be unique, and is used to avoid collisions during successive runs.
	 * @param property The dynamic resources property for the job
	 * @param data Extra information regarding dynamic resources usage - the name of the current
	 * job, an unique identifier for this job, the value of the parameter named by 'injectedId'
	 * in property and the matrix configuration
	 * @return An almost complete configuration for a dynamic resource (lacks job reservation)
	 */
	private static synchronized Map<?, ?> getDynamicResConfig(DynamicResourcesProperty property,
															  DynamicInfoData data) {
		Map<String, String> configuration = new HashMap<String, String>();

		configuration.put(DYNAMICRES_TOKENID, data.tokenValue);

		Set<String> ignoredAxis = new HashSet<String>();
		if (property.getIgnoredAxis() != null)
			ignoredAxis.addAll(Arrays.asList(property.getIgnoredAxis().split("\\s+")));

		if (data.configuration != null) {
			for(Map.Entry mapE : data.configuration)
				if(! ignoredAxis.contains(mapE.getKey().toString()))
					configuration.put(mapE.getKey().toString(), mapE.getValue().toString());
		}

		/* the size should be at least 2, since one of the elements is the token */
		return configuration.size() < 2 ? null : configuration;
	}

	/**
	 * Method called to forcefully update the dynamic information for the job linked to the
	 * given DynamicResourcesProperty.
	 * @param property The dynamic resources property of the job
	 * @param data Extra information regarding dynamic resources usage - the name of the current
	 * job, an unique identifier for this job, the value of the token created for the job
	 * and the matrix configuration
	 */
	private static synchronized void updateJobDynamicInfo(DynamicResourcesProperty property,
															DynamicInfoData data) {

		Map<?, ?> configWithoutJobReservation = getDynamicResConfig(property, data);

		Set<Map<?, ?>> createResources  = new HashSet<Map<?, ?>>();
		Set<Map<?, ?>> consumeResources = new HashSet<Map<?, ?>>();

		if(property.getGeneratedForJobs() != null)
			for(String job : property.getGeneratedForJobs().split("\\s+")) {
				Map newConfig = new HashMap(configWithoutJobReservation);
				newConfig.put("RESERVED_FOR_JOB", job);

				createResources.add(newConfig);
			}

		if(property.getConsumeDynamicResources()) {
			Map newConfig = new HashMap(configWithoutJobReservation);
			newConfig.put("RESERVED_FOR_JOB", data.jobName);

			consumeResources.add(newConfig);
		}

		DynamicResourcesManager.setJobDynamicInfo(data.uniqueJobName, createResources, consumeResources);
	}

	/**
	 * Method called to update the dynamic information for the job linked to the given
	 * DynamicResourcesProperty, if it does not already exist.
	 * @param property The dynamic resources property of the job
	 * @param data Extra information regarding dynamic resources usage - the name of the current
	 * job, an unique identifier for this job, the value of the token created for the job
	 * and the matrix configuration
	 */
	public static synchronized void updateJobDynamicInfoIfRequired(DynamicResourcesProperty property,
																	 DynamicInfoData data) {
		if(DynamicResourcesManager.getJobDynamicInfo(data.uniqueJobName) == null)
			updateJobDynamicInfo(property, data);
	}

	/**
	 * Method called to retrieve the dynamic information about dynamic resource creation
	 * for the job linked to the given DynamicResourcesProperty. If the info is not available,
	 * it will be updated.
	 * @param property The dynamic resources property of the job
	 * @param data Extra information regarding dynamic resources usage - the name of the current
	 * job, an unique identifier for this job, the value of the token created for the job
	 * and the matrix configuration
	 * @return The dynamic information regarding dynamic resource creation
	 */
	public static synchronized Set<Map<?, ?>> getJobDynamicInfoCreate(DynamicResourcesProperty property,
																		DynamicInfoData data) {
		updateJobDynamicInfoIfRequired(property, data);

		return DynamicResourcesManager.getJobWillCreate(data.uniqueJobName);
	}

	/**
	 * Method called to retrieve the dynamic information about dynamic resource consumption
	 * for the job linked to the given DynamicResourcesProperty. If the info is not available,
	 * it will be updated.
	 * @param property The dynamic resources property of the job
	 * @param data Extra information regarding dynamic resources usage - the name of the current
	 * job, an unique identifier for this job, the value of the token created for the job
	 * and the matrix configuration
	 * @return The dynamic information regarding dynamic resource consumption
	 */
	public static synchronized Set<Map<?, ?>> getJobDynamicInfoConsume(DynamicResourcesProperty property,
																		 DynamicInfoData data) {
		updateJobDynamicInfoIfRequired(property, data);

		return DynamicResourcesManager.getJobWillConsume(data.uniqueJobName);
	}

	/**
	 * Retrieves the value of a token created for the item's task, based on the name
	 * of its master job and next build number
	 * @param item An item whose task is used to create a name to identify the token
	 * @return The value of the token
	 */
	public static String getProjectToken(Queue.Item item) {
		AbstractProject<?, ?> proj = (AbstractProject<?, ?>) item.task;

		String projectUniqueName = getCurrentRunTokenID(proj);
		String currentTime = tokens.get(projectUniqueName);

		if(currentTime == null) {
			currentTime = String.valueOf(System.currentTimeMillis());
			tokens.put(projectUniqueName, currentTime);
		}

		return currentTime;
	}

	/**
	 * Retrieves the value of a token created for the build's project, based on the name
	 * of its master job and next build number
	 * @param build A build whose project is used to create a name to identify the token
	 * @return The value of the token
	 */
	public static String getProjectToken(AbstractBuild<?, ?> build) {
		AbstractProject<?, ?> proj = build.getProject();

		String projectUniqueName = getCurrentRunTokenID(proj);
		String currentTime = tokens.get(projectUniqueName);

		if(currentTime == null) {
			currentTime = String.valueOf(System.currentTimeMillis());
			tokens.put(projectUniqueName, currentTime);
		}

		return currentTime;
	}

	/**
	 * Creates tokens for all projects that will be triggered by the build's master job, with the same
	 * token as the one used by the build itself. If no token is found for the build, the current time
	 * will be used instead.
	 * <p>
	 * Only downstream projects that use dynamic resources are considered.
	 * @param build The build whose master job will trigger other jobs
	 */
	public static void createTokensForDownstream(AbstractBuild<?, ?> build) {
		AbstractProject<?, ?> proj = build.getProject();
		if(proj instanceof MatrixConfiguration)
			proj = (AbstractProject<?, ?>) proj.getParent();

		String projectUniqueName = getCurrentRunTokenID(proj);
		String currentTime = tokens.get(projectUniqueName);
		currentTime = currentTime == null ? String.valueOf(System.currentTimeMillis()) : currentTime;

		for(AbstractProject<?, ?> downstream : proj.getDownstreamProjects()) {
			DynamicResourcesProperty downProperty = getDynamicProperty(downstream);

			if(downProperty != null)
				tokens.put(getFutureRunTokenID(downstream), currentTime);
		}

		tokens.remove(projectUniqueName);
	}

	/**
	 * Method used to delete the dynamic information for the job linked to the build and its token.
	 * <p>
	 * Usually called in 'onCompleted' and 'onDeleted' methods in LockRunListener.
	 * @param currentBuild The build whose dynamic information is removed
	 */
	public static synchronized void buildEnded(AbstractBuild<?, ?> currentBuild) {
		String uniqueName = getUniqueName(currentBuild);

		DynamicResourcesManager.destroyJobDynamicInfo(uniqueName);
		tokens.remove(uniqueName);
	}
}
