package org.jenkins.plugins.lockableresources;

import hudson.Extension;
import hudson.Util;
import hudson.model.AutoCompletionCandidates;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GetLockStep extends Step {
    private static final Logger LOGGER = Logger.getLogger(GetLockStep.class.getName());

    @CheckForNull
    private String resource;

    @CheckForNull
    private String label;

    private int quantity = 0;

    @DataBoundConstructor
    public GetLockStep() {
    }

    @DataBoundSetter
    public void setResource(String resource) {
        this.resource = Util.fixEmpty(resource);
    }

    public String getResource() {
        return resource;
    }

    @DataBoundSetter
    public void setLabel(String label) {
        this.label = Util.fixEmpty(label);
    }

    public String getLabel() {
        return label;
    }

    @DataBoundSetter
    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public int getQuantity() {
        return quantity;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(this, context);
    }

    /**
     * Label and resource are mutual exclusive.
     */
    public void validate() throws Exception {
        if (label != null && !label.isEmpty() && resource !=  null && !resource.isEmpty()) {
            throw new IllegalArgumentException("Label and resource name cannot be specified simultaneously.");
        }
    }



    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "getLock";
        }

        @Override
        public String getDisplayName() {
            return "Acquires a lock on shared resources";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.singleton(TaskListener.class);
        }

        public AutoCompletionCandidates doAutoCompleteResource(@QueryParameter String value) {
            return RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames(value);
        }

        public static FormValidation doCheckLabel(@QueryParameter String value, @QueryParameter String resource) {
            return LockStep.DescriptorImpl.doCheckLabel(value, resource);
        }

        public static FormValidation doCheckResource(@QueryParameter String value, @QueryParameter String label) {
            return doCheckLabel(label, value);
        }
    }

    public static class Execution extends StepExecution {

        private static final long serialVersionUID = 1L;

        private transient final GetLockStep step;

        Execution(GetLockStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        public boolean start() throws Exception {
            step.validate();

            LockUtils.queueLock(step, step.getResource(), step.getLabel(), step.getQuantity(), false, getContext());

            return false;
        }

        @Override
        public void stop(@Nonnull Throwable cause) {
            boolean cleaned = LockableResourcesManager.get().unqueueContext(getContext());
            if (!cleaned) {
                LOGGER.log(Level.WARNING, "Cannot remove context from lockable resource witing list. The context is not in the waiting list.");
            }
            getContext().onFailure(cause);

        }

        @Override
        public String getStatus() {
            LockableResourcesStruct struct = LockableResourcesManager.get().getResourceForQueuedContext(getContext());
            if (struct == null) {
                return "waiting without a pending lock";
            } else {
                if (struct.label != null) {
                    return "waiting on label " + struct.label;
                } else {
                    return "waiting on resources " + struct.required;
                }
            }
        }

        public static void proceed(@Nonnull List<String> resourceNames, @Nonnull StepContext context,
                                   @CheckForNull String resourceDescription, boolean inversePrecedence) {
            try {
                context.get(TaskListener.class).getLogger().println("Lock acquired on [" + resourceDescription + "]");
                context.get(FlowNode.class).addAction(new LockedResourcesAction(resourceNames, resourceDescription, inversePrecedence));
                context.onSuccess(null);
            } catch (Exception e) {
                context.onFailure(e);
            }
        }
    }
}
