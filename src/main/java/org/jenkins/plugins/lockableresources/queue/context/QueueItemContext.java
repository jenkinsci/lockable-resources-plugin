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
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.Collection;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.jobProperty.RequiredResourcesProperty;
import org.jenkins.plugins.lockableresources.resources.RequiredResources;

/*
 * This class is used to queue free style builds
 */
public class QueueItemContext extends QueueContext {
    private transient RequiredResourcesProperty property = null;
    private transient Queue.Item item = null;

    /*
     * Constructor for the QueuedContextStruct class.
     */
    public QueueItemContext(Queue.Item item) {
        this.item = item;
    }
    
    public Queue.Item getItem() {
        return item;
    }
    
    private RequiredResourcesProperty getProperty() {
        if(property == null && item != null) {
            Job<?, ?> project = Utils.getProject(item);
            this.property = RequiredResourcesProperty.getFromProject(project);
        }
        return property;
    }

    @Override
    public long getQueueId() {
        if(item == null) {
            return 0;
        }
        return item.getId();
    }
    
    @Override
    public Run<?, ?> getBuild() {
        return null;
    }
    
    @Override
    public TaskListener getListener() {
        return null;
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
        if(item == null) {
            return new EnvVars();
        }
        return Utils.getEnvVars(item);
    }
    
    @Override
    public boolean isStillApplicable() {
        if(item == null) {
            // After Jenkins crash, for exemple
            return false;
        }
        Queue.Item currentItem = Jenkins.getInstance().getQueue().getItem(getQueueId());
        return (currentItem != null) && (item.task == currentItem.task);
    }
}
