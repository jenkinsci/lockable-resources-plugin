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
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;

public final class Utils {
  private Utils() {}

  @CheckForNull
  public static Job<?, ?> getProject(@NonNull Queue.Item item) {
    if (item.task instanceof Job) return (Job<?, ?>) item.task;
    return null;
  }

  @CheckForNull
  public static Job<?, ?> getProject(@NonNull Run<?, ?> build) {
    return build.getParent();
  }

  @CheckForNull
  public static LockableResourcesStruct requiredResources(@NonNull Job<?, ?> project) {
    EnvVars env = new EnvVars();

    if (project instanceof MatrixConfiguration) {
      env.putAll(((MatrixConfiguration) project).getCombination());
      project = (Job<?, ?>) project.getParent();
    }

    RequiredResourcesProperty property = project.getProperty(RequiredResourcesProperty.class);
    if (property != null) return new LockableResourcesStruct(property, env);

    return null;
  }
}
