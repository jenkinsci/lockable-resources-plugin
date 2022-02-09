package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.AutoCompletionCandidates;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class LockStep extends Step implements Serializable {

  private static final long serialVersionUID = -953609907239674360L;

  @CheckForNull public String resource = null;

  @CheckForNull public String label = null;
  @CheckForNull public String anyOfLabels = null;
  @CheckForNull public String allOfLabels = null;
  @CheckForNull public String noneOfLabels = null;

  public int quantity = 0;

  /** name of environment variable to store locked resources in */
  @CheckForNull public String variable = null;

  public boolean inversePrecedence = false;

  public boolean skipIfLocked = false;

  @CheckForNull public List<LockStepResource> extra = null;

  // it should be LockStep() - without params. But keeping this for backward compatibility
  // so `lock('resource1')` still works and `lock(label: 'label1', quantity: 3)` works too (resource
  // is not required)
  @DataBoundConstructor
  public LockStep(@Nullable String resource) {
    if (StringUtils.isNotBlank(resource)) {
      this.resource = resource;
    }
  }

  @DataBoundSetter
  public void setInversePrecedence(boolean inversePrecedence) {
    this.inversePrecedence = inversePrecedence;
  }

  @DataBoundSetter
  public void setSkipIfLocked(boolean skipIfLocked) {
    this.skipIfLocked = skipIfLocked;
  }

  @DataBoundSetter
  public void setLabel(String label) {
    if (StringUtils.isNotBlank(label)) {
      this.label = label;
    }
  }

  @DataBoundSetter
  public void setAnyOfLabels(String anyOfLabels) {
    if (StringUtils.isNotBlank(anyOfLabels)) {
      this.anyOfLabels = anyOfLabels;
    }
  }

  @DataBoundSetter
  public void setAllOfLabels(String allOfLabels) {
    if (allOfLabels != null && !allOfLabels.isEmpty()) {
      this.allOfLabels = allOfLabels;
    }
  }

  @DataBoundSetter
  public void setNoneOfLabels(String noneOfLabels) {
    if (StringUtils.isNotBlank(noneOfLabels)) {
      this.noneOfLabels = noneOfLabels;
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
  public void setExtra(@CheckForNull List<LockStepResource> extra) {
    this.extra = extra;
  }

  @Extension
  public static final class DescriptorImpl extends StepDescriptor {

    @Override
    public String getFunctionName() {
      return "lock";
    }

    @NonNull
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

    @Override
    public Set<Class<?>> getRequiredContext() {
      return Collections.singleton(TaskListener.class);
    }
  }

  @Override
  public String toString() {
    if (extra != null && !extra.isEmpty()) {
      return getResources().stream()
        .map(res -> "{" + res.toString() + "}")
        .collect(Collectors.joining(","));
    } else if (resource != null || label != null || anyOfLabels != null || allOfLabels != null || noneOfLabels != null) {
      return LockStepResource.toString(resource, label, anyOfLabels, allOfLabels, noneOfLabels, quantity);
    } else {
      return "nothing";
    }
  }

  /** Label and resource are mutual exclusive. */
  public void validate() {
    LockStepResource.validate(resource, label, anyOfLabels, allOfLabels, noneOfLabels, quantity);
  }

  public List<LockStepResource> getResources() {
    List<LockStepResource> resources = new ArrayList<>();
    if (resource != null || label != null || anyOfLabels != null || allOfLabels != null || noneOfLabels != null) {
      resources.add(new LockStepResource(resource, label, anyOfLabels, allOfLabels, noneOfLabels, quantity));
    }

    if (extra != null) {
      resources.addAll(extra);
    }
    return resources;
  }

  @Override
  public StepExecution start(StepContext context) {
    return new LockStepExecution(this, context);
  }
}
