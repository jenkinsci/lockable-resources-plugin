/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2016, Florian Hug. All rights reserved.             *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import hudson.model.Run;
import java.io.IOException;
import java.io.Serializable;
import javax.annotation.Nullable;
import org.jenkins.plugins.lockableresources.step.LockStep;
import org.jenkinsci.plugins.workflow.steps.StepContext;

/*
 * This class is used to queue pipeline contexts
 * which shall be executed once the necessary
 * resources are free'd.
 */
public class QueuedContextStruct implements Serializable {
    private static final long serialVersionUID = 1L;
    /**
     * Reference to the pipeline step context.
     */
    private final StepContext context;
    /**
     * Reference to the lock step
     */
    private final LockStep step;

    /*
     * Constructor for the QueuedContextStruct class.
     */
    public QueuedContextStruct(StepContext context, LockStep step) {
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

    @Nullable
    public Run<?, ?> getBuild() {
        try {
            return context.get(Run.class);
        } catch(IOException | InterruptedException ex) {
            return null;
        }
    }
}
