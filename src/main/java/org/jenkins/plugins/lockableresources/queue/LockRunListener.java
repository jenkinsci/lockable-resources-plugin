/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.matrix.MatrixBuild;
import hudson.model.AbstractBuild;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.util.Collection;
import java.util.Set;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import org.jenkins.plugins.lockableresources.BackwardCompatibility;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;
import org.jenkins.plugins.lockableresources.actions.ResourceVariableNameAction;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.resources.RequiredResources;

@Extension
public class LockRunListener extends RunListener<Run<?, ?>> {
    private static final String LOG_PREFIX = "[lockable-resources]";
    private static final Logger LOGGER = Logger.getLogger(LockRunListener.class.getName());

    /**
     * Backward compatibility
     */
    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void initBackwardCompatibility() {
        BackwardCompatibility.init();
    }

    @Override
    public void onStarted(Run<?, ?> build, TaskListener listener) {
        if(checkBuildType(build)) {
            return;
        }
        if(build instanceof AbstractBuild) {
            Job<?, ?> project = Utils.getProject(build);
            LockableResourcesManager manager = LockableResourcesManager.get();
            Collection<RequiredResources> requiredResourcesList = manager.getProjectRequiredResources(project);
            Set<LockableResource> selected = manager.getQueuedResourcesFromProject(project.getFullName());
            boolean locked = manager.lock(selected, requiredResourcesList, build, null, false);
            if(locked) {
                listener.getLogger().printf("%s acquired lock on %s%n", LOG_PREFIX, selected);
                for(RequiredResources requiredResources : requiredResourcesList) {
                    if(requiredResources.getVariableName() != null) {
                        build.addAction(new ResourceVariableNameAction(new StringParameterValue(
                                requiredResources.getVariableName(),
                                selected.toString().replaceAll("[\\]\\[]", ""))));
                    }
                }
            }
            build.addAction(LockedResourcesBuildAction.fromResources(selected));
        }
    }

    @Override
    public void onCompleted(Run<?, ?> build, @Nonnull TaskListener listener) {
        if(checkBuildType(build)) {
            return;
        }
        Set<LockableResource> requiredResources = LockableResourcesManager.get().getLockedResourcesFromBuild(build);
        if(requiredResources.size() > 0) {
            LockableResourcesManager.get().unlock(requiredResources, build);
            listener.getLogger().printf("%s released lock on %s%n", LOG_PREFIX, requiredResources);
            LOGGER.fine(build.getFullDisplayName() + " released lock on " + requiredResources);
        }
    }

    @Override
    public void onDeleted(Run<?, ?> build) {
        if(checkBuildType(build)) {
            return;
        }
        Set<LockableResource> requiredResources = LockableResourcesManager.get().getLockedResourcesFromBuild(build);
        if(requiredResources.size() > 0) {
            LockableResourcesManager.get().unlock(requiredResources, build);
            LOGGER.fine(build.getFullDisplayName() + " released lock on " + requiredResources);
        }
    }

    private boolean checkBuildType(Run<?, ?> build) {
        // Skip unlocking for multiple configuration projects,
        // only the child jobs will actually unlock resources.
        return build instanceof MatrixBuild;
    }
}
