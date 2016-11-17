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
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.matrix.MatrixProject;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import java.util.Collection;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.jenkins.plugins.lockableresources.BackwardCompatibility;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.jobProperty.RequiredResourcesProperty;
import org.jenkins.plugins.lockableresources.queue.context.QueueItemContext;
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.resources.RequiredResources;

@Extension
public class LockableResourcesQueueTaskDispatcher extends QueueTaskDispatcher {
    /**
     * Backward compatibility
     */
    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void initBackwardCompatibility() {
        BackwardCompatibility.init();
    }

    @Override
    public CauseOfBlockage canRun(Queue.Item item) {
        // Skip locking for multiple configuration projects,
        // only the child jobs will actually lock resources.
        if(item.task instanceof MatrixProject) {
            return null;
        }
        Job<?, ?> project = Utils.getProject(item);
        if(project == null) {
            return null;
        }
        RequiredResourcesProperty property = RequiredResourcesProperty.getFromProject(project);
        if(property == null) {
            return null;
        }
        return getCauseOfBlockage(project, item);
    }

    @CheckForNull
    private static CauseOfBlockage getCauseOfBlockage(@Nonnull Job<?, ?> project, @Nonnull Queue.Item item) {
        // At this stage, a RUN instance is not yet created, so we can not lock resources
        // Instead, the resources are queued (temporary reserved), if available, and will be locked in LockRunListener.onStarted()
        // If there is no enough free resources, a blocage is raised and the item remains in Jenkins tasks queue
        LockableResourcesManager manager = LockableResourcesManager.get();
        QueueItemContext queueContext = new QueueItemContext(item);
        if(manager.tryQueue(project, queueContext)) {
            return null;
        } else {
            EnvVars env = queueContext.getEnvVars();
            return new BecauseResourcesLocked(project, env);
        }
    }

    private static class BecauseResourcesLocked extends CauseOfBlockage {
        private final Collection<RequiredResources> requiredResourcesList;
        private final EnvVars env;

        BecauseResourcesLocked(Job<?, ?> project, EnvVars env) {
            RequiredResourcesProperty property = RequiredResourcesProperty.getFromProject(project);
            if(property == null) {
                this.requiredResourcesList = null;
            } else {
                this.requiredResourcesList = property.getRequiredResourcesList();
            }
            this.env = env;
        }

        @Override
        public String getShortDescription() {
            return "Waiting for resources " + RequiredResources.toString(requiredResourcesList, env);
        }
    }
}
