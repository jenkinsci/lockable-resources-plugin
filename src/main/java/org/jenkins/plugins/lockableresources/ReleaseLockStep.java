package org.jenkins.plugins.lockableresources;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkins.plugins.lockableresources.actions.LockedFlowNodeAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.LinearScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.NodeStepTypePredicate;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Collections;
import java.util.Set;

public class ReleaseLockStep extends Step {

    @DataBoundConstructor
    public ReleaseLockStep() {
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new ReleaseLockStep.Execution(context);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "releaseLock";
        }

        @Override
        public String getDisplayName() {
            return "Releases the previous lock acquired by the getLock step";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.singleton(TaskListener.class);
        }

    }

    public static class Execution extends SynchronousStepExecution<Void> {

        Execution(StepContext context) {
            super(context);
        }

        @Override
        public Void run() throws Exception {
            FlowNode thisNode = getContext().get(FlowNode.class);
            LinearScanner scanner = new LinearScanner();

            FlowNode getLock = scanner.findFirstMatch(thisNode, new NodeStepTypePredicate("getLock"));

            if (getLock != null) {
                LockedFlowNodeAction action = getLock.getAction(LockedFlowNodeAction.class);
                if (action != null && !action.isReleased()) {
                    LockableResourcesManager.get().unlockNames(action.getResourceNames(), getContext().get(Run.class),
                            action.isInversePrecedence());
                    action.release();
                    getContext().get(TaskListener.class).getLogger()
                            .println("Lock released on resource [" + action.getResourceDescription()+ "]");
                } else {
                    // Last getLock has no action, meaning it never really locked anything, so...eh?
                    getContext().get(TaskListener.class).getLogger().println("No active lock, proceeding.");
                }
            } else {
                // No previous lock step, so we should error out, maybe? Not sure yet. Just gonna log for now.
                getContext().get(TaskListener.class).getLogger().println("No previous getLock step, proceeding.");
            }
            return null;
        }
    }
}
