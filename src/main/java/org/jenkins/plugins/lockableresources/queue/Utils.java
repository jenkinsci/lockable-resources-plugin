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
import hudson.PluginWrapper;
import hudson.matrix.MatrixConfiguration;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;

public final class Utils {
    private Utils() {}

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
    public static LockableResourcesStruct requiredResources(@NonNull Job<?, ?> project) {
        EnvVars env = new EnvVars();

        if (isMatrixConfiguration(project)) {
          env.putAll(((MatrixConfiguration) project).getCombination());
            project = (Job<?, ?>) project.getParent();
        }

        RequiredResourcesProperty property = project.getProperty(RequiredResourcesProperty.class);
        if (property != null) return new LockableResourcesStruct(property, env);

        return null;
    }

    // check if matrix plugin is installed
    public static boolean isMatrixPluginEnabled() {
        PluginWrapper matrixPlugin = Jenkins.get().getPluginManager().getPlugin("matrix-project");
        // check installation
        if (matrixPlugin == null) {
            return false;
        }

        return matrixPlugin.isEnabled();
    }

    public static boolean isMatrixProject(Job<?, ?> project) {
        if (!isMatrixPluginEnabled() || project == null) return false;
        return project.getClass().getName().equals("hudson.matrix.MatrixProject");
    }

    public static boolean isMatrixBuild(Run<?, ?> build) {
        if (!isMatrixPluginEnabled() || build == null) return false;
        return build.getParent().getClass().getName().equals("hudson.matrix.MatrixBuild");
    }
    // is matrix configuration
    public static boolean isMatrixConfiguration(Job<?, ?> project) {
        if (!isMatrixPluginEnabled() || project == null) return false;
        return project.getClass().getName().equals("hudson.matrix.MatrixConfiguration");
    }
}
