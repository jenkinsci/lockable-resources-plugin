package org.jenkins.plugins.lockableresources;

import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkinsci.plugins.workflow.steps.MissingContextVariableException;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public class LockUtils {

    public static void queueLock(@Nonnull Step step, @CheckForNull String resource, String label, int quantity,
                                 boolean inversePrecedence, @Nonnull StepContext context) throws Exception {
        Run<?,?> run = context.get(Run.class);
        TaskListener listener = context.get(TaskListener.class);

        if (listener == null) {
            throw new MissingContextVariableException(TaskListener.class);
        }

        boolean nonBlock = step instanceof GetLockStep;
        listener.getLogger().println("Trying to acquire lock on [" + step + "]");
        List<String> resources = new ArrayList<String>();
        if (resource != null) {
            if (LockableResourcesManager.get().createResource(resource)) {
                listener.getLogger().println("Resource [" + step + "] did not exist. Created.");
            }
            resources.add(resource);
        }
        LockableResourcesStruct resourceHolder = new LockableResourcesStruct(resources, label, quantity);
        // determine if there are enough resources available to proceed
        List<LockableResource> available = LockableResourcesManager.get().checkResourcesAvailability(resourceHolder, listener.getLogger(), null);
        if (available == null || !LockableResourcesManager.get().lock(available, run, context, step.toString(), inversePrecedence,
                nonBlock)) {
            listener.getLogger().println("[" + step + "] is locked, waiting...");
            LockableResourcesManager.get().queueContext(context, resourceHolder, step.toString(), nonBlock);
        } // proceed is called inside lock if execution is possible
    }
}
