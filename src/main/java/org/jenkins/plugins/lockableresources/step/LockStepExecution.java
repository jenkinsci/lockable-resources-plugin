package org.jenkins.plugins.lockableresources.step;

import com.google.inject.Inject;
import hudson.EnvVars;
import hudson.Util;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.Utils;
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
        EnvVars env = Utils.getEnvVars(run, listener);

        for(RequiredResources rr : step.requiredResourcesList) {
            // create missing resources only when specified explicitly
            String rsrcs = rr.getExpandedResources(env);
            if(Util.fixEmpty(rsrcs) != null) {
                for(String resource : Utils.splitLabels(rsrcs)) {
                    if(manager.createResource(resource)) {
                        listener.getLogger().println("Resource [" + rsrcs + "] did not exist. Created.");
                    }
                }
            }
        }

        listener.getLogger().println("Trying to acquire lock on [" + step.requiredResourcesList + "]");
        // Determine if there are enough resources available to proceed
        // Function 'proceed' is called inside lock if execution is possible
        // Else, the task is queued for later retry
        Set<LockableResource> selected = manager.selectFreeResources(step.requiredResourcesList, null, env);
        if(selected == null || !LockableResourcesManager.get().lock(selected, step.requiredResourcesList, run, getContext(), step.inversePrecedence)) {
            listener.getLogger().println(step + " is locked, waiting...");
            LockableResourcesManager.get().queueContext(getContext(), step.requiredResourcesList);
        }
        return false; //asynchronous step execution
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        boolean cleaned = LockableResourcesManager.get().unqueueContext(getContext());
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
     * @param inversePrecedence
     */
    public static void proceed(Collection<String> resourceNames, Collection<RequiredResources> requiredresources, StepContext context, boolean inversePrecedence) {
        Run<?, ?> r;
        try {
            r = context.get(Run.class);
            context.get(TaskListener.class).getLogger().println("Lock acquired on " + requiredresources);
            context.get(TaskListener.class).getLogger().println("Lock resources " + resourceNames);
        } catch(IOException | InterruptedException e) {
            context.onFailure(e);
            return;
        }
        LOGGER.finest("Lock acquired on " + resourceNames + " by " + r.getExternalizableId());
        context.newBodyInvoker().
                withCallback(new Callback(resourceNames, requiredresources, inversePrecedence)).
                withDisplayName("Locking " + resourceNames).
                start();
    }

    private static final class Callback extends BodyExecutionCallback.TailCall {
        private static final long serialVersionUID = 1L;
        private final Collection<String> resourceNames;
        private final boolean inversePrecedence;
        private final Collection<RequiredResources> requiredresources;

        Callback(Collection<String> resourceNames, Collection<RequiredResources> requiredresources, boolean inversePrecedence) {
            this.resourceNames = resourceNames;
            this.inversePrecedence = inversePrecedence;
            this.requiredresources = requiredresources;
        }

        @Override
        protected void finished(StepContext context) throws Exception {
            LockableResourcesManager manager = LockableResourcesManager.get();
            Set<LockableResource> resources = manager.getResourcesFromNames(resourceNames);
            Run<?, ?> build = context.get(Run.class);
            if(build == null) {
                LOGGER.warning("No valid build during resources unlocking: may lead to blocked resources");
            } else {
                manager.unlock(resources, build, context, inversePrecedence);
            }
            context.get(TaskListener.class).getLogger().println("Lock released on " + requiredresources);
            context.get(TaskListener.class).getLogger().println("Unlock resources " + resourceNames);
            LOGGER.finest("Lock released on " + resources);
        }
    }
}
