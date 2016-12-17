/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2016, Florian Hug. All rights reserved.             *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue.context;

import hudson.EnvVars;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.Collection;
import javax.annotation.Nonnull;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.jobProperty.RequiredResourcesProperty;
import org.jenkins.plugins.lockableresources.resources.RequiredResources;

/*
 * This class is used to queue free style builds
 */
public class QueueBuildContext extends QueueContext {
    private transient Run<?, ?> build = null;
    private transient RequiredResourcesProperty property = null;
    private transient final String buildExternalizableId;
    private transient final TaskListener listener;

    /*
     * Constructor for the QueuedContextStruct class.
     */
    public QueueBuildContext(@Nonnull Run<?, ?> build, @Nonnull TaskListener listener) {
        super();
        this.build = build;
        this.buildExternalizableId = build.getExternalizableId();
        this.listener = listener;
    }
    
    private RequiredResourcesProperty getProperty() {
        if(property == null && build != null) {
            Job<?, ?> project = Utils.getProject(build);
            this.property = RequiredResourcesProperty.getFromProject(project);
        }
        return property;
    }
    
    @Override
    public long getQueueId() {
        if(getBuild() == null) {
            return 0;
        }
        return build.getQueueId();
    }

    @Override
    public Run<?, ?> getBuild() {
        if(build == null && buildExternalizableId != null) {
            build = Run.fromExternalizableId(buildExternalizableId);
        }
        return build;
    }
    
    @Override
    public TaskListener getListener() {
        return listener;
    }
    
    @Override
    public Collection<RequiredResources> getRequiredResources() {
        if(getProperty() == null) {
            return null;
        }
        return getProperty().getRequiredResourcesList();
    }

    @Override
    public String getVariableName() {
        if(getProperty() == null) {
            return null;
        }
        return getProperty().getVariableName();
    }

    @Override
    public EnvVars getEnvVars() {
        return Utils.getEnvVars(build, listener);
    }
    
    @Override
    public boolean isStillApplicable() {
        if(build == null) {
            return false;
        }
        if(!super.isStillApplicable()) {
            return false;
        }
        Run<?, ?> currentBuild = Run.fromExternalizableId(build.getExternalizableId());
        return (build == currentBuild);
    }
}
