/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                       *
 *                                                                           *
 * Resource reservation per node by Darius Mihai (mihai_darius22@yahoo.com)  *
 * Copyright (C) 2015 Freescale Semiconductor, Inc.                          *
 *                                                                           *
 * This file is part of the Jenkins Lockable Resources Plugin and is         *
 * published under the MIT license.                                          *
 *                                                                           *
 * See the "LICENSE.txt" file for more information.                          *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import hudson.EnvVars;
import hudson.matrix.MatrixConfiguration;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Queue;

import hudson.model.Run;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;

public class Utils {


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
		RequiredResourcesProperty property = null;
		EnvVars env = new EnvVars();

		if (project instanceof MatrixConfiguration) {
			env.putAll(((MatrixConfiguration) project).getCombination());
			project = (Job<?, ?>) project.getParent();
		}

		property = project.getProperty(RequiredResourcesProperty.class);
		if (property != null)
			return new LockableResourcesStruct(property, env);

		return null;
	}

	/**
	 * @return The name of the node that hosts the executor running this method
	 */
	public static String getNodeName() {
		Executor exec = Executor.currentExecutor();

		if (exec == null)
			return "";

		Computer c = exec.getOwner();
		Node node = c.getNode();
		String nodeName = null;

		if(node != null)
			nodeName = node.getNodeName();

		return "".equals(nodeName) ? "master" : nodeName;
	}
}
