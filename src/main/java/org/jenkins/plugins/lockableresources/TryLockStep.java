package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.AutoCompletionCandidates;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class TryLockStep extends Step implements Serializable {

  private static final long serialVersionUID = 1L;

  @CheckForNull public String resource = null;

  @DataBoundConstructor
  public TryLockStep() {}

  @DataBoundSetter
  public void setResource(final String resource) {
    this.resource = resource;
  }

  @Extension
  public static final class DescriptorImpl extends StepDescriptor {

    @Override
    public String getFunctionName() {
      return "tryLock";
    }

    @Override
    public String getDisplayName() {
      return "Try to lock shared resource";
    }

    @Override
    public boolean takesImplicitBlockArgument() {
      return false;
    }

    public AutoCompletionCandidates doAutoCompleteResource(@QueryParameter final String value) {
      return RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames(value);
    }

    public static FormValidation doCheckResource(
        @QueryParameter final String value, @QueryParameter final String resource) {
      return LockStepResource.DescriptorImpl.doCheckResource(resource, null);
    }

    @Override
    public Set<Class<?>> getRequiredContext() {
      return Collections.singleton(TaskListener.class);
    }
  }

  public LockAttributes toLockAttributes() {
    final boolean inversePrecedence = false;
    final List<LockStepResource> extra = new ArrayList<>();
    final String label = null;
    final int quantity = 1;
    final String variable = null;
    return new LockAttributes(extra, resource, label, quantity, variable, inversePrecedence);
  }

  @Override
  public StepExecution start(final StepContext context) {
    return new SynchronousNonBlockingStepExecution<Object>(context) {

      private static final long serialVersionUID = 1L;

      @Override
      protected Object run() throws Exception {
        getContext().get(FlowNode.class).addAction(new PauseAction("TryLock"));
        final boolean queIfLocked = false;
        return LockHelper.start(queIfLocked, toLockAttributes(), getContext());
      }
    };
  }
}
