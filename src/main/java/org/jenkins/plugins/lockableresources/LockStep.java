package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

public class LockStep extends Step implements Serializable {

  private static final long serialVersionUID = -953609907239674360L;

  @CheckForNull public String resource = null;

  @CheckForNull public String label = null;

  public int quantity = 0;

  /** name of environment variable to store locked resources in */
  @CheckForNull public String variable = null;

  public boolean inversePrecedence = false;

  public String resourceSelectStrategy = ResourceSelectStrategy.SEQUENTIAL.name();

  public boolean skipIfLocked = false;

  @CheckForNull public List<LockStepResource> extra = null;

  // it should be LockStep() - without params. But keeping this for backward compatibility
  // so `lock('resource1')` still works and `lock(label: 'label1', quantity: 3)` works too (resource
  // is not required)
  @DataBoundConstructor
  public LockStep(@Nullable String resource) {
    if (resource != null && !resource.isEmpty()) {
      this.resource = resource;
    }
  }

  @DataBoundSetter
  public void setInversePrecedence(boolean inversePrecedence) {
    this.inversePrecedence = inversePrecedence;
  }

  @DataBoundSetter
  public void setResourceSelectStrategy(String resourceSelectStrategy) {
    this.resourceSelectStrategy = resourceSelectStrategy;
  }

  @DataBoundSetter
  public void setSkipIfLocked(boolean skipIfLocked) {
    this.skipIfLocked = skipIfLocked;
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
      return Messages.LockStep_displayName();
    }

    @Override
    public boolean takesImplicitBlockArgument() {
      return true;
    }

    @RequirePOST
    public AutoCompletionCandidates doAutoCompleteResource(@QueryParameter String value,
      @AncestorInPath Item item) {
      return RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames(value, item);
    }

    @RequirePOST
    public static FormValidation doCheckLabel(
      @QueryParameter String value,
      @QueryParameter String resource,
      @AncestorInPath Item item) {
      return LockStepResource.DescriptorImpl.doCheckLabel(value, resource, item);
    }

    @RequirePOST
    public static FormValidation doCheckResource(
      @QueryParameter String value,
      @QueryParameter String label,
      @AncestorInPath Item item) {
      return LockStepResource.DescriptorImpl.doCheckLabel(label, value, item);
    }

    @RequirePOST
    public static FormValidation doCheckResourceSelectStrategy(
      @QueryParameter String resourceSelectStrategy,
      @AncestorInPath Item item) {
      // check permission, security first
      if (item != null) {
        item.checkPermission(Item.CONFIGURE);
      } else {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      }
      if (resourceSelectStrategy != null && !resourceSelectStrategy.isEmpty()) {
        try {
          ResourceSelectStrategy.valueOf(resourceSelectStrategy.toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException e) {
          return FormValidation.error(Messages.error_invalidResourceSelectionStrategy(resourceSelectStrategy, Arrays.stream(ResourceSelectStrategy.values()).map(Enum::toString).map(strategy -> strategy.toLowerCase(Locale.ENGLISH)).collect(Collectors.joining(", "))));
        }
      }
      return FormValidation.ok();
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
    } else if (resource != null || label != null) {
      return LockStepResource.toString(resource, label, quantity);
    } else {
      return "nothing";
    }
  }

  /** Label and resource are mutual exclusive. */
  public void validate() {
    LockStepResource.validate(resource, label, resourceSelectStrategy);
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

  @Override
  public StepExecution start(StepContext context) {
    return new LockStepExecution(this, context);
  }
}
