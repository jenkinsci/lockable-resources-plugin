/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.matrix.MatrixBuild;
import hudson.model.AbstractBuild;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourceProperty;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;
import org.jenkins.plugins.lockableresources.actions.ResourceVariableNameAction;

@Extension
public class LockRunListener extends RunListener<Run<?, ?>> {

    static final String LOG_PREFIX = "[lockable-resources]";
    static final Logger LOGGER = Logger.getLogger(LockRunListener.class.getName());

    @Override
    public void onStarted(Run<?, ?> build, TaskListener listener) {
        // Skip locking for multiple configuration projects,
        // only the child jobs will actually lock resources.
        if (build instanceof MatrixBuild) return;

        if (build instanceof AbstractBuild) {
            Job<?, ?> proj = Utils.getProject(build);
            List<LockableResource> required = new ArrayList<>();
            LockableResourcesStruct resources = Utils.requiredResources(proj);

            if (resources != null) {
                if (resources.requiredNumber != null
                        || !resources.label.isEmpty()
                        || resources.getResourceMatchScript() != null) {
                    required.addAll(LockableResourcesManager.get().getResourcesFromProject(proj.getFullName()));
                } else {
                    required.addAll(resources.required);
                }

                if (LockableResourcesManager.get().lock(required, build, null)) {
                    build.addAction(LockedResourcesBuildAction.fromResources(required));
                    listener.getLogger().printf("%s acquired lock on %s%n", LOG_PREFIX, required);
                    LOGGER.fine(build.getFullDisplayName() + " acquired lock on " + required);
                    if (resources.requiredVar != null) {
                        List<StringParameterValue> envsToSet = new ArrayList<>();

                        // add the comma separated list of names acquired
                        envsToSet.add(new StringParameterValue(
                                resources.requiredVar,
                                required.stream().map(LockableResource::getName).collect(Collectors.joining(","))));

                        // also add a numbered variable for each acquired lock along with properties of the lock
                        int index = 0;
                        for (LockableResource lr : required) {
                            String lockEnvName = resources.requiredVar + index;
                            envsToSet.add(new StringParameterValue(lockEnvName, lr.getName()));
                            for (LockableResourceProperty lockProperty : lr.getProperties()) {
                                String propEnvName = lockEnvName + "_" + lockProperty.getName();
                                envsToSet.add(new StringParameterValue(propEnvName, lockProperty.getValue()));
                            }
                            ++index;
                        }

                        build.addAction(new ResourceVariableNameAction(envsToSet));
                    }
                } else {
                    listener.getLogger().printf("%s failed to lock %s%n", LOG_PREFIX, required);
                    LOGGER.fine(build.getFullDisplayName() + " failed to lock " + required);
                }
            }
        }
    }

    @Override
    public void onCompleted(Run<?, ?> build, @NonNull TaskListener listener) {
        // Skip unlocking for multiple configuration projects,
        // only the child jobs will actually unlock resources.
        if (build instanceof MatrixBuild) return;

        // obviously project name cannot be obtained here
        List<LockableResource> required = LockableResourcesManager.get().getResourcesFromBuild(build);
        if (!required.isEmpty()) {
            LockableResourcesManager.get().unlock(required, build);
            listener.getLogger().printf("%s released lock on %s%n", LOG_PREFIX, required);
            LOGGER.fine(build.getFullDisplayName() + " released lock on " + required);
        }
    }

    @Override
    public void onDeleted(Run<?, ?> build) {
        // Skip unlocking for multiple configuration projects,
        // only the child jobs will actually unlock resources.
        if (build instanceof MatrixBuild) return;

        List<LockableResource> required = LockableResourcesManager.get().getResourcesFromBuild(build);
        if (!required.isEmpty()) {
            LockableResourcesManager.get().unlock(required, build);
            LOGGER.fine(build.getFullDisplayName() + " released lock on " + required);
        }
    }
}
