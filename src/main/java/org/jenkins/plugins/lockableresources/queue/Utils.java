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
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;

import hudson.model.Run;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;

import javax.annotation.Nonnull;

public final class Utils {
    private Utils() {
    }

    /**
     * Pattern for capturing variables. Either $xyz, ${xyz} or ${a.b} but not $a.b
     */
    private static final Pattern VARIABLE = Pattern.compile("\\$([A-Za-z0-9_]+|\\{[A-Za-z0-9_.]+\\})");

    /**
     * Pattern for capturing parameters. ${xyz} but not $${xyz}
     */
    private static final Pattern PARAMETER = Pattern.compile("(?<!\\$)\\$\\{([A-Za-z0-9_.]+)\\}");

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
			Job<?, ?> project, EnvVars env) {
		RequiredResourcesProperty property = null;

		if (project instanceof MatrixConfiguration) {
			env.putAll(((MatrixConfiguration)project).getCombination());
			project = (Job<?, ?>)project.getParent();
		}

		property = project.getProperty(RequiredResourcesProperty.class);
		if (property != null)
			return new LockableResourcesStruct(property, env);

		return null;
	}

    @Nonnull
    public static List<String> getProjectParameterNames(AbstractProject<?,?> project) {
        ParametersDefinitionProperty params = project.getProperty(ParametersDefinitionProperty.class);
        if (params != null)
            return params.getParameterDefinitionNames();
        return Collections.emptyList();
    }

    public static boolean isParameter(String s) {
        return PARAMETER.matcher(s).matches();
    }

    public static boolean containsParameter(String s) {
        return PARAMETER.matcher(s).find();
    }

    @Nonnull
    public static List<String> checkParameters(String s, AbstractProject<?, ?> project) {
        List<String> unknownParameters = new ArrayList<>();
        List<String> paramNames = getProjectParameterNames(project);
        Matcher m = PARAMETER.matcher(s);
        while (m.find()) {
            String param = m.group(1);
            if (!paramNames.contains(param)) {
                unknownParameters.add(param);
            }
        }
        return unknownParameters;
    }
}
