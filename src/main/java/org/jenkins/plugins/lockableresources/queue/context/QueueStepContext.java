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
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.resources.RequiredResources;
import org.jenkins.plugins.lockableresources.step.LockStep;
import org.jenkinsci.plugins.workflow.steps.StepContext;

/*
 * This class is used to queue pipeline contexts
 * which shall be executed once the necessary
 * resources are free'd.
 */
public class QueueStepContext extends QueueContext implements Serializable {
    private static final long serialVersionUID = 1L;
    /**
     * Reference to the pipeline step context.
     */
    private final StepContext context;
    /**
     * Reference to the lock step
     */
    private final LockStep step;

    /**
     * Constructor for the QueuedContextStruct class.
     *
     * @param context
     * @param step
     */
    public QueueStepContext(StepContext context, LockStep step) {
        this.context = context;
        this.step = step;
    }

    /**
     * Gets the pipeline step context.
     *
     * @return
     */
    public StepContext getContext() {
        return this.context;
    }

    /**
     * Gets the lock step
     *
     * @return
     */
    public LockStep getStep() {
        return this.step;
    }

    @Override
    public long getQueueId() {
        Run<?, ?> build = getBuild();
        if(build == null) {
            return 0;
        }
        return build.getQueueId();
    }

    @Override
    public Run<?, ?> getBuild() {
        try {
            return context.get(Run.class);
        } catch(IOException | InterruptedException ex) {
            return null;
        }
    }

    @Override
    public TaskListener getListener() {
        try {
            return context.get(TaskListener.class);
        } catch(IOException | InterruptedException ex) {
        }
        return null;
    }

    @Override
    public Collection<RequiredResources> getRequiredResources() {
        return step.getRequiredResources();
    }

    @Override
    public String getVariableName() {
        return step.getVariable();
    }

    @Override
    public EnvVars getEnvVars() {
        Run<?, ?> run = null;
        TaskListener listener = null;
        if(context != null) {
            try {
                run = context.get(Run.class);
            } catch(IOException | InterruptedException e) {
            }
            try {
                listener = context.get(TaskListener.class);
            } catch(IOException | InterruptedException e) {
            }
        }
        return Utils.getEnvVars(run, listener);
    }

    @Override
    public boolean isStillApplicable() {
        if(context == null) {
            return false;
        }
        Run<?, ?> build;
        try {
            build = context.get(Run.class);
            if(build == null) {
                return false;
            }
            Run<?, ?> currentBuild = Run.fromExternalizableId(build.getExternalizableId());
            return (build == currentBuild);
        } catch(IOException | InterruptedException ex) {
            return false;
        }
    }

    @Override
    public boolean equals(Object obj) {
        if(obj instanceof QueueStepContext) {
            return ((QueueStepContext) obj).getContext().equals(context);
        } else {
            return super.equals(obj);
        }
    }

    @Override
    public int hashCode() {
        if(context == null) {
            return super.hashCode();
        }
        return context.hashCode(); //To change body of generated methods, choose Tools | Templates.
    }
}
