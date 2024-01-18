/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2016, Florian Hug. All rights reserved.             *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/*
 * This class is used to queue pipeline contexts
 * which shall be executed once the necessary
 * resources are free'd.
 */
public class QueuedContextStruct implements Serializable {

    /*
     * Reference to the pipeline step context.
     */
    private StepContext context;

    /*
     * Reference to the resources required by the step context.
     */
    private List<LockableResourcesStruct> lockableResourcesStruct;

    /*
     * Description of the required resources used within logging messages.
     */
    private String resourceDescription;

    /*
     * Name of the variable to save the locks taken.
     */
    private String variableName;

    private long queuedAt = 0;

    // cached candidates
    public transient List<String> candidates = null;

    private static final Logger LOGGER = Logger.getLogger(QueuedContextStruct.class.getName());

    /*
     * Constructor for the QueuedContextStruct class.
     */
    @Restricted(NoExternalUse.class)
    public QueuedContextStruct(
            StepContext context,
            List<LockableResourcesStruct> lockableResourcesStruct,
            String resourceDescription,
            String variableName) {
        this.context = context;
        this.lockableResourcesStruct = lockableResourcesStruct;
        this.resourceDescription = resourceDescription;
        this.variableName = variableName;
        this.queuedAt = new Date().getTime();
    }

    /*
     * Gets the pipeline step context.
     */
    @Restricted(NoExternalUse.class)
    public StepContext getContext() {
        return this.context;
    }

    /** Return build, where is the resource used. */
    @CheckForNull
    @Restricted(NoExternalUse.class)
    public Run<?, ?> getBuild() {
        try {
            if (this.getContext() == null) {
                return null;
            }
            return this.getContext().get(Run.class);
        } catch (Exception e) {
            // for some reason there is no Run object for this context
            LOGGER.log(
                    Level.WARNING,
                    "Cannot get the build object from the context to proceed with lock. The build probably does not exists (deleted?)",
                    e);
            return null;
        }
    }

    @Restricted(NoExternalUse.class)
    public boolean isValid() {
        Run<?, ?> run = this.getBuild();
        if (run == null || run.isBuilding() == false) {
            // skip this one, for some reason there is no Run object for this context
            LOGGER.warning("The queue " + this + " will be removed, because the build does not exists");
            return false;
        }
        return true;
    }

    @Restricted(NoExternalUse.class)
    /*
     * Gets the required resources.
     */
    public List<LockableResourcesStruct> getResources() {
        return this.lockableResourcesStruct;
    }

    @Restricted(NoExternalUse.class)
    /*
     * Gets the resource description for logging messages.
     */
    public String getResourceDescription() {
        return this.resourceDescription;
    }

    @Restricted(NoExternalUse.class)
    /*
     * Gets the variable name to save the locks taken.
     */
    public String getVariableName() {
        return this.variableName;
    }

    /** Get time-ticks, when the item has been added into queue */
    @Restricted(NoExternalUse.class)
    public long getAddTime() {
        return queuedAt;
    }

    @Restricted(NoExternalUse.class)
    public String toString() {
        return "build: "
                + this.getBuild()
                + " resources: "
                + this.getResourceDescription()
                + " added at: "
                + this.getAddTime();
    }

    @Restricted(NoExternalUse.class)
    public PrintStream getLogger() {
        PrintStream logger = null;
        try {
            TaskListener taskListener = this.getContext().get(TaskListener.class);
            if (taskListener != null) {
                logger = taskListener.getLogger();
            }
        } catch (IOException | InterruptedException e) {
            LOGGER.log(Level.FINE, "Could not get logger for next context: " + e, e);
        }
        return logger;
    }

    private static final long serialVersionUID = 1L;
}
