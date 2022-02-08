package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AutoCompletionCandidates;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import java.io.Serializable;
import java.util.Collections;
import java.util.Set;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class UpdateLockStep extends Step implements Serializable {

  private static final long serialVersionUID = -7955849755535282258L;

  @CheckForNull
  public String resource = null;

  @CheckForNull
  public String addLabels = null;

  @CheckForNull
  public String setLabels = null;

  @CheckForNull
  public String removeLabels = null;

  @CheckForNull
  public String setNote = null;

  public boolean createResource = false;
  public boolean deleteResource = false;

  @DataBoundSetter
  public void setResource(String resource) {
    this.resource = resource;
  }

  @DataBoundSetter
  public void setAddLabels(String addLabels) {
    this.addLabels = addLabels;
  }

  @DataBoundSetter
  public void setSetLabels(String setLabels) {
    this.setLabels = setLabels;
  }

  @DataBoundSetter
  public void setRemoveLabels(String removeLabels) {
    this.removeLabels = removeLabels;
  }

  @DataBoundSetter
  public void setCreateResource(boolean createResource) {
    this.createResource = createResource;
  }

  @DataBoundSetter
  public void setDeleteResource(boolean deleteResource) {
    this.deleteResource = deleteResource;
  }

  @DataBoundSetter
  public void setSetNote(String setNote) {
    this.setNote = setNote;
  }

  @DataBoundConstructor
  public UpdateLockStep() {
  }


  @Extension
  public static final class DescriptorImpl extends StepDescriptor {

    @Override
    public String getFunctionName() {
      return "updateLock";
    }

    @NonNull
    @Override
    public String getDisplayName() {
      return "Update the definition of a lock";
    }

    @Override
    public boolean takesImplicitBlockArgument() {
      return false;
    }

    public AutoCompletionCandidates doAutoCompleteResource(@QueryParameter String value) {
      return RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames(value);
    }

    public static FormValidation doCheckResource(
      @QueryParameter String value) {
      return UpdateLockStepResource.DescriptorImpl.doCheckResource(value);
    }

    public static FormValidation doCheckAddLabels(
      @QueryParameter String value, @QueryParameter String setLabels) {
      return UpdateLockStepResource.DescriptorImpl.doCheckLabelOperations(value, setLabels);
    }

    public static FormValidation doCheckRemoveLabels(
      @QueryParameter String value, @QueryParameter String setLabels) {
      return UpdateLockStepResource.DescriptorImpl.doCheckLabelOperations(value, setLabels);
    }

    public static FormValidation doCheckDelete(
      @QueryParameter boolean value, @QueryParameter String setLabels, @QueryParameter String addLabels, @QueryParameter String removeLabels, @QueryParameter String setNote, @QueryParameter boolean createResource) {
      return UpdateLockStepResource.DescriptorImpl.doCheckDelete(value, setLabels,addLabels, removeLabels, setNote, createResource);
    }

    @Override
    public Set<Class<?>> getRequiredContext() {
      return Collections.singleton(TaskListener.class);
    }
  }

  @Override
  public StepExecution start(StepContext context) {
    return new UpdateLockStepExecution(this, context);
  }

  public void validate() {
    if (StringUtils.isBlank(resource)) {
      throw new IllegalArgumentException("The resource name must be specified.");
    }
  }
}
