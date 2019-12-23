package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.AutoCompletionCandidates;
import hudson.util.FormValidation;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class LockStep extends AbstractStepImpl implements Serializable {

  private static final long serialVersionUID = 1L;

  @CheckForNull public String resource = null;

  @CheckForNull public String label = null;

  public int quantity = 0;

  /** name of environment variable to store locked resources in */
  @CheckForNull public String variable = null;

  public boolean inversePrecedence = false;

  @CheckForNull public List<LockStepResource> extra = null;

  // it should be LockStep() - without params. But keeping this for backward compatibility
  // so `lock('resource1')` still works and `lock(label: 'label1', quantity: 3)` works too (resource
  // is not required)
  @DataBoundConstructor
  public LockStep(String resource) {
    if (resource != null && !resource.isEmpty()) {
      this.resource = resource;
    }
  }

  @DataBoundSetter
  public void setInversePrecedence(boolean inversePrecedence) {
    this.inversePrecedence = inversePrecedence;
  }

  @DataBoundSetter
  public void setLabel(String label) {
    if (label != null && !label.isEmpty()) {
      this.label = label;
    }
  }

  @DataBoundSetter
  public void setVariable(String variable) {
    if (variable != null && !variable.isEmpty()) {
      this.variable = variable;
    }
  }

  @DataBoundSetter
  public void setQuantity(int quantity) {
    this.quantity = quantity;
  }

  @DataBoundSetter
  public void setExtra(List<LockStepResource> extra) {
    this.extra = extra;
  }

  @Extension
  public static final class DescriptorImpl extends AbstractStepDescriptorImpl {

    public DescriptorImpl() {
      super(LockStepExecution.class);
    }

    @Override
    public String getFunctionName() {
      return "lock";
    }

    @Override
    public String getDisplayName() {
      return "Lock shared resource";
    }

    @Override
    public boolean takesImplicitBlockArgument() {
      return true;
    }

    public AutoCompletionCandidates doAutoCompleteResource(@QueryParameter String value) {
      return RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames(value);
    }

    public static FormValidation doCheckLabel(
        @QueryParameter String value, @QueryParameter String resource) {
      return LockStepResource.DescriptorImpl.doCheckLabel(value, resource);
    }

    public static FormValidation doCheckResource(
        @QueryParameter String value, @QueryParameter String label) {
      return LockStepResource.DescriptorImpl.doCheckLabel(label, value);
    }
  }

  public String toString() {
    if (extra != null && !extra.isEmpty()) {
      return getResources().stream()
          .map(resource -> "{" + resource.toString() + "}")
          .collect(Collectors.joining(","));
    } else if (resource != null || label != null) {
      return LockStepResource.toString(resource, label, quantity);
    } else {
      return "nothing";
    }
  }

  /** Label and resource are mutual exclusive. */
  public void validate() throws Exception {
    LockStepResource.validate(resource, label, quantity);
  }

  public List<LockStepResource> getResources() {
    List<LockStepResource> resources = new ArrayList<>();
    if (resource != null || label != null) {
      resources.add(new LockStepResource(resource, label, quantity));
    }

    if (extra != null) {
      resources.addAll(extra);
    }
    return resources;
  }
}
