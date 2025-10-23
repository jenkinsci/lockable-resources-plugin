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
import hudson.ExtensionList;
import hudson.matrix.MatrixConfiguration;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import java.util.Map;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;
import org.jenkinsci.plugins.variant.OptionalExtension;

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

        for (var ma : ExtensionList.lookup(MatrixAssist.class)) {
            env.putAll(ma.getCombination(project));
            project = ma.getMainProject(project);
        }

        RequiredResourcesProperty property = project.getProperty(RequiredResourcesProperty.class);
        if (property != null) return new LockableResourcesStruct(property, env);

        return null;
    }

    public interface MatrixAssist {
        @NonNull
        Map<String, String> getCombination(@NonNull Job<?, ?> project);

        @NonNull
        Job<?, ?> getMainProject(@NonNull Job<?, ?> project);
    }

    @OptionalExtension(requirePlugins = "matrix-project")
    public static final class MatrixImpl implements MatrixAssist {
        @Override
        public Map<String, String> getCombination(Job<?, ?> project) {
            return project instanceof MatrixConfiguration mc ? mc.getCombination() : Map.of();
        }

        @Override
        public Job<?, ?> getMainProject(Job<?, ?> project) {
            return project instanceof MatrixConfiguration mc ? mc.getParent() : project;
        }
    }
}
