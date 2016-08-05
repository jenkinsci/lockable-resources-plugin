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
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;

public class Utils {

	public static Job<?, ?> getProject(Queue.Item item) {
		if (item.task instanceof Job) {
			return (Job<?, ?>) item.task;
		}

		return null;
	}

	public static Job<?, ?> getProject(Run<?, ?> build) {
		return build.getParent();
	}

	public static Map<String, Object> getParams(Queue.Item item) {
		Map<String, Object> params = new HashMap<>();

		if (item.task instanceof MatrixConfiguration) {
			MatrixConfiguration matrix = (MatrixConfiguration) item.task;

			params.putAll(matrix.getCombination());
		}

		return params;
	}

	public static Map<String, Object> getParams(Job<?, ?> project) {
		Map<String, Object> params = new HashMap<>();

		if (project instanceof MatrixConfiguration) {
			MatrixConfiguration matrix = (MatrixConfiguration) project;

			params.putAll(matrix.getCombination());
		}

		return params;
	}

}
