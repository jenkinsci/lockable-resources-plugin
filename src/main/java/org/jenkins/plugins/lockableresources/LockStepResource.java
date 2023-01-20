package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.util.FormValidation;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

public class LockStepResource extends AbstractDescribableImpl<LockStepResource> implements Serializable {

  @CheckForNull
  public String resource = null;

  @CheckForNull
  public String label = null;

  public int quantity = 0;

  LockStepResource(@Nullable String resource, @Nullable String label, int quantity) {
    this.resource = resource;
    this.label = label;
    this.quantity = quantity;
  }

  @DataBoundConstructor
  public LockStepResource(@Nullable String resource) {
    if (resource != null && !resource.isEmpty()) {
      this.resource = resource;
    }
  }

  @DataBoundSetter
  public void setLabel(String label) {
    if (label != null && !label.isEmpty()) {
      this.label = label;
    }
  }

  @DataBoundSetter
  public void setQuantity(int quantity) {
    this.quantity = quantity;
  }

  @Override
  public String toString() {
    return toString(resource, label, quantity);
  }

  public static String toString(String resource, String label, int quantity) {
    // a label takes always priority
    if (label != null) {
      if (quantity > 0) {
        return "Label: " + label + ", Quantity: " + quantity;
      }
      return "Label: " + label;
    }
    // make sure there is an actual resource specified
    if (resource != null) {
      return resource;
    }
    return "[no resource/label specified - probably a bug]";
  }

  /**
   * Label and resource are mutual exclusive.
   */
  public void validate() {
    validate(resource, label, null);
  }

  /**
   * Label and resource are mutual exclusive.
   * The label, if provided, must be configured (at least one resource must have this label).
   */
  public static void validate(String resource, String label, String resourceSelectStrategy) {
    if (label != null && !label.isEmpty() && resource !=  null && !resource.isEmpty()) {
      throw new IllegalArgumentException(Messages.error_labelAndNameSpecified());
    }
    if (label != null && !LockableResourcesManager.get().isValidLabel( label ) ) {
      throw new IllegalArgumentException(Messages.error_labelDoesNotExist(label));
    }
    if (resourceSelectStrategy != null ) {
      try {
        ResourceSelectStrategy.valueOf(resourceSelectStrategy.toUpperCase(Locale.ENGLISH));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(Messages.error_invalidResourceSelectionStrategy(resourceSelectStrategy, Arrays.stream(ResourceSelectStrategy.values()).map(Enum::toString).map(strategy -> strategy.toLowerCase(Locale.ENGLISH)).collect(Collectors.joining(", "))));
      }
    }
  }

  private static final long serialVersionUID = 1L;

  @Extension
  public static class DescriptorImpl extends Descriptor<LockStepResource> {

    @NonNull
    @Override
    public String getDisplayName() {
      return Messages.LockStepResource_displayName();
    }

    @RequirePOST
    public AutoCompletionCandidates doAutoCompleteResource(@QueryParameter String value,
      @AncestorInPath Item item) {
      return RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames(value, item);
    }

    @RequirePOST
    public static FormValidation doCheckLabel(@QueryParameter String value,
      @QueryParameter String resource,
      @AncestorInPath Item item) {
      // check permission, security first
      if (item != null) {
        item.checkPermission(Item.CONFIGURE);
      } else {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      }

      String resourceLabel = Util.fixEmpty(value);
      String resourceName = Util.fixEmpty(resource);
      if (resourceLabel != null && resourceName != null) {
        return FormValidation.error(Messages.error_labelAndNameSpecified());
      }
      if ((resourceLabel == null) && (resourceName == null)) {
        return FormValidation.error(Messages.error_labelOrNameMustBeSpecified());
      }
      if (resourceLabel != null && !LockableResourcesManager.get().isValidLabel(resourceLabel)) {
        return FormValidation.error(Messages.error_labelDoesNotExist(resourceLabel));
      }
      return FormValidation.ok();
    }

    @RequirePOST
    public static FormValidation doCheckResource(@QueryParameter String value,
      @QueryParameter String label,
      @AncestorInPath Item item) {
      return doCheckLabel(label, value, item);
    }
  }
}
