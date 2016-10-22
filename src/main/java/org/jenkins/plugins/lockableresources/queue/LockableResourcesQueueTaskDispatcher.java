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
import java.util.Set;
import javax.annotation.Nullable;
import org.jenkins.plugins.lockableresources.BackwardCompatibility;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
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
        LockableResourcesManager manager = LockableResourcesManager.get();
        Collection<RequiredResources> requiredResourcesList = manager.getProjectRequiredResources(project);
        if(requiredResourcesList == null) {
            return null;
        }
        return getCauseOfBlockage(project, item);
    }

    @Nullable
    private static CauseOfBlockage getCauseOfBlockage(Job<?, ?> project, Queue.Item item) {
        Set<LockableResource> selected = LockableResourcesManager.get().queue(project, item);
        if(selected == null) {
            EnvVars env = Utils.getEnvVars(item);
            return new BecauseResourcesLocked(project, env);
        } else {
            return null;
        }
    }

    private static class BecauseResourcesLocked extends CauseOfBlockage {
        private final Collection<RequiredResources> requiredResourcesList;
        private final EnvVars env;

        BecauseResourcesLocked(Job<?, ?> project, EnvVars env) {
            LockableResourcesManager manager = LockableResourcesManager.get();
            this.requiredResourcesList = manager.getProjectRequiredResources(project);
            this.env = env;
        }

        @Override
        public String getShortDescription() {
            StringBuilder lbl = new StringBuilder("Waiting for resources");
            if(requiredResourcesList != null) {
                for(RequiredResources rr : requiredResourcesList) {
                    if(rr.getExpandedResources(env).isEmpty()) {
                        lbl.append(" ").append(rr.getExpandedLabels(env));
                    } else {
                        lbl.append(" ").append(rr.getExpandedResources(env));
                    }
                }
            }
            return lbl.toString();
        }
    }
}
