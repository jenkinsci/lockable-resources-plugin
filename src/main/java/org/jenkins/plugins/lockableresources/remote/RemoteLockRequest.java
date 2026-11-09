/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.stream.Collectors;
import org.jenkins.plugins.lockableresources.LockStep;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Lock-semantics payload for the remote lock API.
 * Corresponds to the {@code lockRequest} field in {@code POST /acquire}.
 *
 * <p>Routing fields ({@code serverId}, {@code forcedServerId}) are intentionally excluded -
 * they are transport/configuration concerns, not lock semantics.
 */
@Restricted(NoExternalUse.class)
public final class RemoteLockRequest {

    private final String group;
    private final boolean groupOnly;

    public boolean isGroupOnly() {
        return groupOnly;
    }

    public String getGroup() {
        return LockableResource.normalizeGroup(group);
    }

    @CheckForNull
    public String getEffectiveGroup(@NonNull ExtraResource selector) {
        return selector.getGroup() == null ? group : selector.getGroup();
    }

    @CheckForNull
    private final String resource;

    @CheckForNull
    private final String label;

    private final int quantity;

    @CheckForNull
    private final String variable;

    private final boolean inversePrecedence;

    @NonNull
    private final String resourceSelectStrategy;

    private final boolean skipIfLocked;

    @CheckForNull
    private final List<ExtraResource> extra;

    private final int priority;
    private final long timeoutForAllocateResource;

    @NonNull
    private final String timeoutUnit;

    @CheckForNull
    private final String reason;

    public RemoteLockRequest(
            @CheckForNull String resource,
            @CheckForNull String label,
            int quantity,
            @CheckForNull String variable,
            boolean inversePrecedence,
            @NonNull String resourceSelectStrategy,
            boolean skipIfLocked,
            @CheckForNull List<ExtraResource> extra,
            int priority,
            long timeoutForAllocateResource,
            @NonNull String timeoutUnit,
            @CheckForNull String reason) {
        this(
                resource,
                label,
                quantity,
                variable,
                inversePrecedence,
                resourceSelectStrategy,
                skipIfLocked,
                extra,
                priority,
                timeoutForAllocateResource,
                timeoutUnit,
                reason,
                null);
    }

    public RemoteLockRequest(
            @CheckForNull String resource,
            @CheckForNull String label,
            int quantity,
            @CheckForNull String variable,
            boolean inversePrecedence,
            @NonNull String resourceSelectStrategy,
            boolean skipIfLocked,
            @CheckForNull List<ExtraResource> extra,
            int priority,
            long timeoutForAllocateResource,
            @NonNull String timeoutUnit,
            @CheckForNull String reason,
            @CheckForNull String group) {
        this.group = hudson.Util.fixEmptyAndTrim(group);
        this.groupOnly = hudson.Util.fixEmptyAndTrim(group) != null
                && resource == null
                && label == null
                && (extra == null || extra.isEmpty());
        this.resource = resource;
        this.label = label;
        this.quantity = quantity;
        this.variable = variable;
        this.inversePrecedence = inversePrecedence;
        this.resourceSelectStrategy = resourceSelectStrategy;
        this.skipIfLocked = skipIfLocked;
        this.extra = extra;
        this.priority = priority;
        this.timeoutForAllocateResource = timeoutForAllocateResource;
        this.timeoutUnit = timeoutUnit;
        this.reason = reason;
    }

    @CheckForNull
    public String getResource() {
        return resource;
    }

    @CheckForNull
    public String getLabel() {
        return label;
    }

    public int getQuantity() {
        return quantity;
    }

    @CheckForNull
    public String getVariable() {
        return variable;
    }

    public boolean isInversePrecedence() {
        return inversePrecedence;
    }

    @NonNull
    public String getResourceSelectStrategy() {
        return resourceSelectStrategy;
    }

    public boolean isSkipIfLocked() {
        return skipIfLocked;
    }

    @CheckForNull
    public List<ExtraResource> getExtra() {
        return extra;
    }

    public int getPriority() {
        return priority;
    }

    public long getTimeoutForAllocateResource() {
        return timeoutForAllocateResource;
    }

    @NonNull
    public String getTimeoutUnit() {
        return timeoutUnit;
    }

    @CheckForNull
    public String getReason() {
        return reason;
    }

    /**
     * Builds a {@code RemoteLockRequest} from the DSL lock step.
     * {@code serverId} is excluded - it is a routing concern, not part of lock semantics.
     */
    @NonNull
    public static RemoteLockRequest from(@NonNull LockStep step) {
        List<ExtraResource> extra = null;
        if (step.extra != null && !step.extra.isEmpty()) {
            extra = step.extra.stream()
                    .map(r -> new ExtraResource(r.resource, r.label, r.quantity, r.getGroup()))
                    .collect(Collectors.toList());
        }
        return new RemoteLockRequest(
                step.resource,
                step.label,
                step.quantity,
                step.variable,
                step.inversePrecedence,
                step.resourceSelectStrategy,
                step.skipIfLocked,
                extra,
                step.priority,
                step.timeoutForAllocateResource,
                step.timeoutUnit,
                step.reason,
                step.isGroupOnly()
                                || step.resource != null
                                || step.label != null
                                || (step.extra != null && !step.extra.isEmpty())
                        ? step.getGroup()
                        : null);
    }

    // -----------------------------------------------------------------------

    /** Represents an additional resource in a multi-resource lock request. */
    @Restricted(NoExternalUse.class)
    public static final class ExtraResource {
        private final String group;

        @CheckForNull
        public String getGroup() {
            return group;
        }

        @CheckForNull
        private final String resource;

        @CheckForNull
        private final String label;

        private final int quantity;

        public ExtraResource(@CheckForNull String resource, @CheckForNull String label, int quantity) {
            this(resource, label, quantity, null);
        }

        public ExtraResource(
                @CheckForNull String resource, @CheckForNull String label, int quantity, @CheckForNull String group) {
            this.group = hudson.Util.fixEmptyAndTrim(group);
            this.resource = resource;
            this.label = label;
            this.quantity = quantity;
        }

        @CheckForNull
        public String getResource() {
            return resource;
        }

        @CheckForNull
        public String getLabel() {
            return label;
        }

        public int getQuantity() {
            return quantity;
        }
    }
}
