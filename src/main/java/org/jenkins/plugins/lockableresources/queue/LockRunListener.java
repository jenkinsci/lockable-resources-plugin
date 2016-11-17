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
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.util.Set;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jenkins.plugins.lockableresources.BackwardCompatibility;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.jobProperty.RequiredResourcesProperty;
import org.jenkins.plugins.lockableresources.queue.context.QueueBuildContext;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;

@Extension
public class LockRunListener extends RunListener<Run<?, ?>> {
    private static final Logger LOGGER = Logger.getLogger(LockRunListener.class.getName());

    /**
     * Backward compatibility
     */
    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void initBackwardCompatibility() {
        BackwardCompatibility.init();
    }

    // At this point, resources have been queued due to LockableResourcesQueueTaskDispatcher.canRun()
    // Lock is assured
    @Override
    public void onStarted(@Nonnull Run<?, ?> build, @Nonnull TaskListener listener) {
        if(checkBuildType(build)) {
            return;
        }
        if(build instanceof AbstractBuild) {
            Job<?, ?> project = Utils.getProject(build);
            RequiredResourcesProperty property = RequiredResourcesProperty.getFromProject(project);
            if(property == null) {
                return;
            }
            LockableResourcesManager manager = LockableResourcesManager.get();
            QueueBuildContext queueContext = new QueueBuildContext(build, listener);
            if(!manager.tryLock(queueContext)) {
                manager.unqueue(queueContext);
                LOGGER.severe("Impossible to lock resources that shall be queued");
            }
        }
    }

    @Override
    public void onCompleted(@Nonnull Run<?, ?> build, @Nonnull TaskListener listener) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.removeFromLockQueue(build);
        if(checkBuildType(build)) {
            return;
        }
        Set<LockableResource> requiredResources = LockableResourcesManager.get().getLockedResourcesFromBuild(build);
        if(requiredResources.size() > 0) {
            manager.unlock(requiredResources, build, listener);
        }
    }

    @Override
    public void onDeleted(@Nonnull Run<?, ?> build) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.removeFromLockQueue(build);
        if(checkBuildType(build)) {
            return;
        }
        Set<LockableResource> requiredResources = LockableResourcesManager.get().getLockedResourcesFromBuild(build);
        if(requiredResources.size() > 0) {
            manager.unlock(requiredResources, build, null);
        }
    }

    private boolean checkBuildType(@Nullable Run<?, ?> build) {
        // Skip unlocking for multiple configuration projects,
        // only the child jobs will actually unlock resources.
        return build instanceof MatrixBuild;
    }
}
