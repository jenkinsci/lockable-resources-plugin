/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright 2016 Eb.                                                  *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue.context;

import hudson.EnvVars;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.Collection;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.resources.RequiredResources;

/*
 * This interface is used to queue tasks
 */
public abstract class QueueContext {
    public abstract long getQueueId();

    @CheckForNull
    public abstract Run<?, ?> getBuild();

    @CheckForNull
    public abstract TaskListener getListener();

    @CheckForNull
    public abstract Collection<RequiredResources> getRequiredResources();

    @CheckForNull
    public abstract String getVariableName();

    @Nonnull
    public abstract EnvVars getEnvVars();

    public abstract boolean isStillApplicable();

    @Nonnull
    public String getResourcesString() {
        Collection<RequiredResources> requiredResourcesList = getRequiredResources();
        if(requiredResourcesList == null) {
            return "";
        }
        EnvVars env = getEnvVars();
        return RequiredResources.toString(requiredResourcesList, env);
    }
    
    @Nonnull
    public String getId() {
        Run<?, ?> build = getBuild();
        if(build != null) {
            return build.toString();
        }
        Queue.Item item = Jenkins.getInstance().getQueue().getItem(getQueueId());
        if(item != null) {
            return item.task.getDisplayName();
        }
        return "<unknown queue id: " + getQueueId() + ">";
    }
    
    @CheckForNull
    public String getUserId() {
        Run<?, ?> build = getBuild();
        if(build != null) {
            return Utils.getUserId(build);
        }
        Queue.Item item = Jenkins.getInstance().getQueue().getItem(getQueueId());
        if(item != null) {
            return Utils.getUserId(item);
        }
        return null;
    }
    
    @Override
    public String toString() {
        return getId();
    }

    @Override
    public boolean equals(Object obj) {
        if(obj instanceof QueueContext) {
            QueueContext other = (QueueContext) obj;
            return (getBuild() == other.getBuild()) && (getQueueId() == other.getQueueId());
        }
        return false;
    }

    @Override
    public int hashCode() {
        Run<?, ?> build = getBuild();
        if(build == null) {
            return (int) getQueueId();
        }
        return build.hashCode();
    }
}
