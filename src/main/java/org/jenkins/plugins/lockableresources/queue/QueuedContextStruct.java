/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.queue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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

    /*
     * The reason why the resource is being locked.
     */
    private String reason;

    private int priority = 0;

    /*
     * Timeout for waiting to acquire the resource, in the specified timeoutUnit.
     * 0 means no timeout (wait indefinitely).
     */
    private long timeoutForAllocateResource = 0;

    /*
     * Time unit for the timeout. Defaults to MINUTES.
     */
    private String timeoutUnit = "MINUTES";

    /*
     * Pre-computed absolute deadline (epoch millis) when this entry times out.
     * 0 means no timeout. Calculated once at construction time to avoid
     * repeated TimeUnit.valueOf() + toMillis() on every queue check.
     */
    private long timeoutDeadlineMillis = 0;

    // cached candidates
    public transient List<String> candidates = null;

    private static final Logger LOGGER = Logger.getLogger(QueuedContextStruct.class.getName());

    private String id = null;

    /*
     * Constructor for the QueuedContextStruct class.
     */
    @Restricted(NoExternalUse.class)
    public QueuedContextStruct(
            StepContext context,
            List<LockableResourcesStruct> lockableResourcesStruct,
            String resourceDescription,
            String variableName,
            int priority) {
        this(context, lockableResourcesStruct, resourceDescription, variableName, priority, null, 0, "MINUTES");
    }

    /*
     * Constructor for the QueuedContextStruct class with reason and timeout.
     */
    @Restricted(NoExternalUse.class)
    public QueuedContextStruct(
            StepContext context,
            List<LockableResourcesStruct> lockableResourcesStruct,
            String resourceDescription,
            String variableName,
            int priority,
            String reason,
            long timeoutForAllocateResource,
            String timeoutUnit) {
        this.context = context;
        this.lockableResourcesStruct = lockableResourcesStruct;
        this.resourceDescription = resourceDescription;
        this.variableName = variableName;
        this.priority = priority;
        this.reason = reason;
        this.timeoutForAllocateResource = timeoutForAllocateResource;
        this.timeoutUnit = timeoutUnit != null ? timeoutUnit : "MINUTES";
        this.id = UUID.randomUUID().toString();

        // Pre-compute deadline once to avoid repeated calculation on every queue check
        if (timeoutForAllocateResource > 0) {
            try {
                TimeUnit unit = TimeUnit.valueOf(this.timeoutUnit);
                this.timeoutDeadlineMillis = System.currentTimeMillis() + unit.toMillis(timeoutForAllocateResource);
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Invalid timeoutUnit '" + this.timeoutUnit + "', timeout disabled");
                this.timeoutDeadlineMillis = 0;
            }
        }
    }

    @Restricted(NoExternalUse.class)
    public int compare(QueuedContextStruct other) {
        if (this.priority > other.getPriority()) return -1;
        else if (this.priority == other.getPriority()) return 0;
        else return 1;
    }

    @Restricted(NoExternalUse.class)
    public int getPriority() {
        return this.priority;
    }

    @Restricted(NoExternalUse.class)
    public String getId() {
        if (this.id == null) {
            this.id = UUID.randomUUID().toString();
        }
        return this.id;
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

    /**
     * Checks whether this queued context has exceeded its allocation timeout.
     * Uses a pre-computed deadline for performance since this is called on every queue check.
     *
     * @return true if a timeout was set and has expired, false otherwise
     */
    @Restricted(NoExternalUse.class)
    public boolean isTimedOut() {
        return timeoutDeadlineMillis > 0 && System.currentTimeMillis() > timeoutDeadlineMillis;
    }

    /**
     * Returns the pre-computed deadline (epoch millis) when this entry times out.
     * 0 means no timeout is configured.
     */
    @Restricted(NoExternalUse.class)
    public long getTimeoutDeadlineMillis() {
        return this.timeoutDeadlineMillis;
    }

    /**
     * Returns the configured timeout for resource allocation.
     */
    @Restricted(NoExternalUse.class)
    public long getTimeoutForAllocateResource() {
        return this.timeoutForAllocateResource;
    }

    /**
     * Returns the time unit for the allocation timeout.
     */
    @Restricted(NoExternalUse.class)
    public String getTimeoutUnit() {
        return this.timeoutUnit;
    }

    @Restricted(NoExternalUse.class)
    /*
     * Gets the reason for locking.
     */
    public String getReason() {
        return this.reason;
    }

    @Restricted(NoExternalUse.class)
    public String toString() {
        return "build: "
                + this.getBuild()
                + " resources: "
                + this.getResourceDescription()
                + " priority: "
                + this.priority
                + " id: "
                + this.getId();
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
