package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class LockStepResource extends AbstractDescribableImpl<LockStepResource> implements Serializable {

  @CheckForNull
  public String resource = null;

  @CheckForNull
  public String label = null;

  @CheckForNull
  public String anyOfLabels = null;

  @CheckForNull
  public String allOfLabels = null;

  @CheckForNull
  public String noneOfLabels = null;

  public int quantity = 0;

  LockStepResource(
    @Nullable String resource,
    @Nullable String label,
    @Nullable String anyOfLabels,
    @Nullable String allOfLabels,
    @Nullable String noneOfLabels,
    int quantity) {
    this(resource, label, quantity);
    this.anyOfLabels = anyOfLabels;
    this.allOfLabels = allOfLabels;
    this.noneOfLabels = noneOfLabels;
  }

  LockStepResource(@Nullable String resource, @Nullable String label, int quantity) {
    this(resource);
    if (StringUtils.isNotBlank(label)) {
      this.label = label;
    }
    this.quantity = quantity;
  }

  @DataBoundConstructor
  public LockStepResource(@Nullable String resource) {
    if (StringUtils.isNotBlank(resource)) {
      this.resource = resource;
    }
  }

  @DataBoundSetter
  public void setLabel(String label) {
    if (StringUtils.isNotBlank(label)) {
      this.label = label;
    }
  }

  @DataBoundSetter
  public void setQuantity(int quantity) {
    this.quantity = quantity;
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

  @Override
  public String toString() {
    return toString(resource, label, anyOfLabels, allOfLabels, noneOfLabels, quantity);
  }

  public static String toString(String resource, String label, String anyOfLabels, String allOfLabels, String noneOfLabels, int quantity) {
    // a label takes always priority
    if (label != null || anyOfLabels != null || allOfLabels != null || noneOfLabels != null) {
      List<String> desc = new ArrayList<>();
      if (label != null)
        desc.add("Label: " + label);
      if (anyOfLabels != null)
        desc.add("AnyOfLabels: " + anyOfLabels);
      if (allOfLabels != null)
        desc.add("AllOfLabels: " + allOfLabels);
      if (noneOfLabels != null)
        desc.add("AllOfLabels: " + noneOfLabels);
      if (quantity > 0) {
        desc.add("Quantity: " + quantity);
      }
      return desc.stream().collect(Collectors.joining(", "));
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
    validate(resource, label, anyOfLabels, allOfLabels, noneOfLabels, quantity);
  }

  /**
   * Label and resource are mutual exclusive.
   * The label, if provided, must be configured (at least one resource must have this label).
   */
  public static void validate(String resource, String label, String anyOfLabels, String allOfLabels, String noneOfLabels, int quantity) {
    boolean filtersOnLabels =
      StringUtils.isNotBlank(label)
        || StringUtils.isNotBlank(anyOfLabels)
        || StringUtils.isNotBlank(allOfLabels)
        || StringUtils.isNotBlank(noneOfLabels);

    if (filtersOnLabels && StringUtils.isNotBlank(resource)) {
      throw new IllegalArgumentException("Label and resource name cannot be specified simultaneously.");
    }
    if (label != null && !LockableResourcesManager.get().isValidLabel( label ) ) {
      throw new IllegalArgumentException("The label does not exist: " + label);
    }

    // only validate the `allOfLabels` - it would be fine to use invalid labels in the `anyOf` or `noneOf` filters
    if (allOfLabels != null)  {
      Set<String> allLabels = LockableResourcesManager.get().getAllLabels();
      Optional<String> notFoundLabel = Arrays.stream(allOfLabels.split("\\s+")).filter(l -> allLabels.contains(l) == false).findFirst();
      if (notFoundLabel.isPresent()) {
        throw new IllegalArgumentException("The label does not exist: " + notFoundLabel.get());
      }
    }
  }

  private static final long serialVersionUID = 1L;

  @Extension
  public static class DescriptorImpl extends Descriptor<LockStepResource> {

    @NonNull
    @Override
    public String getDisplayName() {
      return "Resource";
    }

    public AutoCompletionCandidates doAutoCompleteResource(@QueryParameter String value) {
      return RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames(value);
    }

    public static FormValidation doCheckLabel(@QueryParameter String value, @QueryParameter String resource) {
      String resourceLabel = Util.fixEmpty(value);
      String resourceName = Util.fixEmpty(resource);
      if (resourceLabel != null && resourceName != null) {
        return FormValidation.error("Label and resource name cannot be specified simultaneously.");
      }
      if ((resourceLabel == null) && (resourceName == null)) {
        return FormValidation.error("Either label or resource name must be specified.");
      }
      if (resourceLabel != null && !LockableResourcesManager.get().isValidLabel(resourceLabel)) {
        return FormValidation.error("The label does not exist: " + resourceLabel);
      }
      return FormValidation.ok();
    }

    public static FormValidation doCheckResource(@QueryParameter String value, @QueryParameter String label) {
      return doCheckLabel(label, value);
    }
  }

}
