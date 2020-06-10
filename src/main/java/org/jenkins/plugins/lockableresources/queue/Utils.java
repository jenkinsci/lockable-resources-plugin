/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.matrix.MatrixConfiguration;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import java.util.regex.Pattern;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;

public final class Utils {
    private Utils() {}

    /**
     * Pattern for capturing variables. Either $xyz, ${xyz} or ${a.b} but not $a.b
     */
    private static final Pattern VARIABLE = Pattern.compile("\\$([A-Za-z0-9_]+|\\{[A-Za-z0-9_.]+\\})");

    @CheckForNull
    public static Job<?, ?> getProject(@NonNull Queue.Item item) {
        if (item.task instanceof Job) return (Job<?, ?>) item.task;
        return null;
    }

    @NonNull
    public static Job<?, ?> getProject(@NonNull Run<?, ?> build) {
        return build.getParent();
    }

    @CheckForNull
    public static LockableResourcesStruct requiredResources(@NonNull Job<?, ?> project,
                                                            EnvVars env) {

        if (project instanceof MatrixConfiguration) {
            env.putAll(((MatrixConfiguration) project).getCombination());
            project = (Job<?, ?>) project.getParent();
        }

        RequiredResourcesProperty property = project.getProperty(RequiredResourcesProperty.class);
        if (property != null) return new LockableResourcesStruct(property, env);

        return null;
    }

    public static boolean isVariable(String name) {
        return VARIABLE.matcher(name).matches();
    }
}
