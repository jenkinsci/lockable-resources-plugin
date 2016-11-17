package org.jenkins.plugins.lockableresources.step;

import com.google.inject.Inject;
import hudson.EnvVars;
import hudson.Util;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.Collection;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.queue.context.QueueStepContext;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.resources.RequiredResources;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;

/**
 * Manage LockStep execution during the pipeline process
 *
 * @author
 */
public class LockStepExecution extends AbstractStepExecutionImpl {
    private static final long serialVersionUID = 1L;
    @Inject(optional = true)
    private LockStep step;
    @StepContextParameter
    private transient Run<?, ?> run;
    @StepContextParameter
    protected transient TaskListener listener = null;
    private static final Logger LOGGER = Logger.getLogger(LockStepExecution.class.getName());

    @Override
    public boolean start() throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        QueueStepContext queueContext = new QueueStepContext(getContext(), step);
        EnvVars env = queueContext.getEnvVars();

        for(RequiredResources rr : step.getRequiredResources()) {
            // create missing resources only when specified explicitly
            String rsrcs = rr.getExpandedResources(env);
            if(Util.fixEmpty(rsrcs) != null) {
                for(String resource : Utils.splitLabels(rsrcs)) {
                    if(manager.createResource(resource)) {
                        if(listener != null) {
                            listener.getLogger().println("Resource [" + rsrcs + "] did not exist. Created.");
                        }
                    }
                }
            }
        }

        if(listener != null) {
            listener.getLogger().println("Trying to acquire lock on [" + step.getRequiredResources() + "]");
        }
        // Determine if there are enough resources available to proceed
        // Function 'proceed' is called inside lock if execution is possible
        // Else, the task is queued for later retry
        if(!manager.tryLock(queueContext)) {
            if(listener != null) {
                listener.getLogger().println(step.getRequiredResources() + " is locked, waiting...");
            }
        }
        return false; //asynchronous step execution
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        boolean cleaned = LockableResourcesManager.get().removeFromLockQueue(getContext());
        if(!cleaned) {
            LOGGER.log(Level.WARNING, "Cannot remove context from lockable resource witing list. The context is not in the waiting list.");
        }
        getContext().onFailure(cause);
    }

    /**
     * Since LockableResource contains transient variables, they cannot be correctly serialized
     * Hence we use their unique resource names
     *
     * @param resourceNames
     * @param requiredresources
     * @param context
     */
    public static void proceed(Collection<String> resourceNames, Collection<RequiredResources> requiredresources, @Nonnull StepContext context) {
        context.newBodyInvoker().
                withCallback(new Callback(resourceNames, requiredresources)).
                withDisplayName("Locking " + resourceNames).
                start();
    }

    private static class Callback extends BodyExecutionCallback.TailCall {
        private static final long serialVersionUID = 1L;
        private final Collection<String> resourceNames;
        private final Collection<RequiredResources> requiredresources;

        Callback(Collection<String> resourceNames, Collection<RequiredResources> requiredresources) {
            this.resourceNames = resourceNames;
            this.requiredresources = requiredresources;
        }

        @Override
        protected void finished(StepContext context) throws Exception {
            LockableResourcesManager manager = LockableResourcesManager.get();
            Set<LockableResource> resources = manager.getResourcesFromNames(resourceNames);
            if(resources == null) {
                LOGGER.warning("Invalid resources names during unlocking: may lead to blocked resources");
            } else {
                Run<?, ?> build = context.get(Run.class);
                TaskListener listener = context.get(TaskListener.class);
                if(build == null) {
                    LOGGER.warning("No valid build during resources unlocking: may lead to blocked resources");
                } else {
                    if(listener != null) {
                        listener.getLogger().println("Lock released on " + requiredresources);
                    }
                    manager.unlock(resources, build, listener);
                }
            }
            LOGGER.finest("Lock released on " + resources);
        }
    }
}
