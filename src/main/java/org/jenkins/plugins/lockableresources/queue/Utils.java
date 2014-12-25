/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import java.util.HashMap;
import java.util.Map;
import hudson.matrix.MatrixConfiguration;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Queue;
import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;

import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;

public class Utils {

	public static Map<String,String> getQueueItemParams(Queue.Item item) {
		Map<String,String> variables = new HashMap<String, String>();
		for( hudson.model.ParametersAction action: item.getActions(hudson.model.ParametersAction.class) ) {
			for(ParameterValue paramValue: action.getParameters() ) {
				if( paramValue instanceof StringParameterValue) {
					StringParameterValue sv = (StringParameterValue)paramValue;
					variables.put(sv.getName(),sv.value);
				}
			}
		}
		return variables;
	}

	public static AbstractProject<?, ?> getProject(Queue.Item item) {
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

	public static AbstractProject<?, ?> getProject(AbstractBuild<?, ?> build) {
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

	public static LockableResourcesStruct requiredResources(
			AbstractProject<?, ?> project, Map<String,String> buildParams) {
		RequiredResourcesProperty property = project
				.getProperty(RequiredResourcesProperty.class);
		if (property != null) {
			return new LockableResourcesStruct(property,buildParams);
		}
		return null;
	}

	public static LockableResourcesStruct requiredResources(
			AbstractBuild<?, ?> build) {
		AbstractProject<? ,?> project = build.getProject();
		build.getBuildVariables();
		RequiredResourcesProperty property = project
				.getProperty(RequiredResourcesProperty.class);
		if (property != null) {
			return new LockableResourcesStruct(property,build.getBuildVariables());
		}
		return null;
	}

}
