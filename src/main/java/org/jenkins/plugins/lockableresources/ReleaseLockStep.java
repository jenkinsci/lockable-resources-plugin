package org.jenkins.plugins.lockableresources;

import hudson.Extension;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class ReleaseLockStep extends Step implements Serializable {

  private static final long serialVersionUID = 1L;

  private static final Logger LOGGER = Logger.getLogger(ReleaseLockStep.class.getName());

  public String resource = null;

  @DataBoundConstructor
  public ReleaseLockStep() {}

  @DataBoundSetter
  public void setResource(final String resource) {
    this.resource = resource;
  }

  @Extension
  public static final class DescriptorImpl extends StepDescriptor {

    @Override
    public String getFunctionName() {
      return "releaseLock";
    }

    @Override
    public String getDisplayName() {
      return "Release lock shared resource";
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
      if (resource == null || resource.isEmpty()) {
        // Will release any acquired locks
        return FormValidation.ok();
      }
      return LockStepResource.DescriptorImpl.doCheckResource(resource, null);
    }

    @Override
    public Set<Class<?>> getRequiredContext() {
      return Collections.singleton(TaskListener.class);
    }
  }

  @Override
  public StepExecution start(final StepContext context) {
    return new SynchronousNonBlockingStepExecution<Object>(context) {

      private static final long serialVersionUID = 1L;

      @Override
      protected Object run() throws Exception {
        final Run<?, ?> build = context.get(Run.class);
        final PrintStream logger = context.get(TaskListener.class).getLogger();
        // obviously project name cannot be obtained here
        final List<LockableResource> acquiredByJob =
            LockableResourcesManager.get().getResourcesFromBuild(build);

        if (acquiredByJob.isEmpty()) {
          logger.println("Cannot release any locks as none are acquired");
          return null;
        }

        List<LockableResource> toRelease = acquiredByJob;
        if (ReleaseLockStep.this.resource != null) {
          toRelease =
              acquiredByJob.stream()
                  .filter(t -> t.getName().equals(ReleaseLockStep.this.resource))
                  .collect(Collectors.toList());
          if (toRelease.isEmpty()) {
            logger.println(
                "Cannot release lock "
                    + ReleaseLockStep.this.resource
                    + " as it was not acquired by the build: "
                    + acquiredByJob);
            return null;
          }
        }

        LockableResourcesManager.get().unlock(toRelease, build);
        logger.println("Lock released on [" + toRelease + "]");
        return null;
      }
    };
  }
}
