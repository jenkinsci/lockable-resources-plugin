package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.util.FormValidation;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

public class LockStepResource extends AbstractDescribableImpl<LockStepResource> implements Serializable {

    private String group;

    /** Null inherits the main lock step's group.
     * @since TODO
     */
    @CheckForNull
    public String getGroup() {
        return group;
    }

    /** @since TODO */
    @DataBoundSetter
    public void setGroup(@Nullable String group) {
        this.group = Util.fixEmptyAndTrim(group);
    }

    @CheckForNull
    public String resource = null;

    @CheckForNull
    @SuppressFBWarnings(value = "PA_PUBLIC_PRIMITIVE_ATTRIBUTE", justification = "Preserve API compatibility.")
    public String label = null;

    @SuppressFBWarnings(value = "PA_PUBLIC_PRIMITIVE_ATTRIBUTE", justification = "Preserve API compatibility.")
    public int quantity = 0;

    /** The reason why this resource is being locked, displayed in the UI while locked. */
    @CheckForNull
    @SuppressFBWarnings(value = "PA_PUBLIC_PRIMITIVE_ATTRIBUTE", justification = "Preserve API compatibility.")
    public String reason = null;

    LockStepResource(@Nullable String resource, @Nullable String label, int quantity) {
        this(resource, label, quantity, null);
    }

    LockStepResource(@Nullable String resource, @Nullable String label, int quantity, @Nullable String reason) {
        this.resource = Util.fixEmptyAndTrim(resource);
        this.label = Util.fixEmptyAndTrim(label);
        this.quantity = quantity;
        this.reason = Util.fixEmptyAndTrim(reason);
    }

    @DataBoundConstructor
    public LockStepResource(@Nullable String resource) {
        this.resource = Util.fixEmptyAndTrim(resource);
    }

    @DataBoundSetter
    public void setLabel(String label) {
        this.label = Util.fixEmptyAndTrim(label);
    }

    @DataBoundSetter
    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    @DataBoundSetter
    public void setReason(String reason) {
        this.reason = Util.fixEmptyAndTrim(reason);
    }

    @Override
    public String toString() {
        if (resource == null && label == null && group != null) {
            return "Group: " + group + (quantity > 0 ? ", Quantity: " + quantity : "");
        }
        String description = toString(resource, label, quantity, reason);
        return group != null && !ResourceGroup.DEFAULT_NAME.equals(group)
                ? description + ", Group: " + group
                : description;
    }

    public static String toString(String resource, String label, int quantity) {
        return toString(resource, label, quantity, null);
    }

    public static String toString(String resource, String label, int quantity, String reason) {
        // a label takes always priority
        StringBuilder sb = new StringBuilder();
        if (label != null) {
            sb.append("Label: ").append(label);
            if (quantity > 0) {
                sb.append(", Quantity: ").append(quantity);
            }
        } else if (resource != null) {
            sb.append("Resource: ").append(resource);
        } else {
            return "[no resource/label specified - probably a bug]";
        }
        if (reason != null && !reason.isEmpty()) {
            sb.append(", Reason: ").append(reason);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    /** Label and resource are mutual exclusive. */
    public void validate(boolean allowEmptyOrNullValues) {
        validate(resource, label, null, group != null, 0, false, allowEmptyOrNullValues);
    }

    // -------------------------------------------------------------------------
    /** Validate input parameters*/
    public static void validate(
            String resource,
            String label,
            String resourceSelectStrategy,
            List<LockStepResource> extra,
            int priority,
            boolean inversePrecedence,
            boolean allowEmptyOrNullValues) {
        validate(
                resource,
                label,
                resourceSelectStrategy,
                extra != null && !extra.isEmpty(),
                priority,
                inversePrecedence,
                allowEmptyOrNullValues);
        if (extra != null) {
            for (LockStepResource e : extra) {
                e.validate(allowEmptyOrNullValues);
            }
        }
    }

    // -------------------------------------------------------------------------
    /**
     * Validate input parameters. If {@link LockableResourcesManager#isFailFastOnInsufficientResources()}
     * is enabled, also check that no label request (main or extra) asks for more resources than exist
     * with that label. Otherwise such a request waits until enough resources exist.
     */
    public static void validate(
            String resource,
            String label,
            int quantity,
            String resourceSelectStrategy,
            List<LockStepResource> extra,
            int priority,
            boolean inversePrecedence,
            boolean allowEmptyOrNullValues) {
        validate(resource, label, resourceSelectStrategy, extra, priority, inversePrecedence, allowEmptyOrNullValues);
        if (!LockableResourcesManager.get().isFailFastOnInsufficientResources()) {
            return;
        }
        validateQuantity(label, quantity);
        if (extra != null) {
            for (LockStepResource e : extra) {
                validateQuantity(e.label, e.quantity);
            }
        }
    }

    // -------------------------------------------------------------------------
    /** The requested quantity must not exceed the number of resources with the given label. */
    private static void validateQuantity(String label, int quantity) {
        if (label == null || quantity <= 0) {
            return;
        }
        int available =
                LockableResourcesManager.get().getResourcesWithLabel(label).size();
        if (quantity > available) {
            throw new IllegalArgumentException(Messages.error_notEnoughResourcesWithLabel(label, quantity, available));
        }
    }

    // -------------------------------------------------------------------------
    /**
     * Label and resource are mutual exclusive. The label, if provided, must be configured (at least
     * one resource must have this label).
     */
    public static void validate(
            String resource,
            String label,
            String resourceSelectStrategy,
            boolean hasExtra,
            int priority,
            boolean inversePrecedence,
            boolean allowEmptyOrNullValues) {

        if (!allowEmptyOrNullValues && !hasExtra && label == null && resource == null) {
            throw new IllegalArgumentException(Messages.error_labelOrNameMustBeSpecified());
        }

        if (priority != 0 && inversePrecedence) {
            throw new IllegalArgumentException(Messages.error_inversePrecedenceAndPriorityAreSet());
        }

        if (label != null && resource != null) {
            throw new IllegalArgumentException(Messages.error_labelAndNameSpecified());
        }
        if (label != null && !LockableResourcesManager.get().isValidLabel(label)) {
            throw new IllegalArgumentException(Messages.error_labelDoesNotExist(label));
        }
        if (resourceSelectStrategy != null) {
            try {
                ResourceSelectStrategy.valueOf(resourceSelectStrategy.toUpperCase(Locale.ENGLISH));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(Messages.error_invalidResourceSelectionStrategy(
                        resourceSelectStrategy,
                        Arrays.stream(ResourceSelectStrategy.values())
                                .map(Enum::toString)
                                .map(strategy -> strategy.toLowerCase(Locale.ENGLISH))
                                .collect(Collectors.joining(", "))));
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

        /** @since TODO */
        @RequirePOST
        public FormValidation doCheckGroup(
                @QueryParameter String value,
                @QueryParameter String resource,
                @QueryParameter String label,
                @AncestorInPath Item item) {
            if (item != null) {
                item.checkPermission(Item.CONFIGURE);
            } else {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            }
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.ok();
            }
            return Jenkins.get()
                    .getDescriptorByType(RequiredResourcesProperty.DescriptorImpl.class)
                    .doCheckGroup(value, resource, label, item);
        }

        @RequirePOST
        public AutoCompletionCandidates doAutoCompleteResource(
                @QueryParameter String value, @AncestorInPath Item item) {
            return RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames(value, item);
        }

        @RequirePOST
        public static FormValidation doCheckLabel(
                @QueryParameter String value, @QueryParameter String resource, @AncestorInPath Item item) {
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
        public static FormValidation doCheckResource(
                @QueryParameter String value, @QueryParameter String label, @AncestorInPath Item item) {
            return doCheckLabel(label, value, item);
        }
    }
}
