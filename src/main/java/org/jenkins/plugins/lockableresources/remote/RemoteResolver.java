/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jenkins.plugins.lockableresources.LockStepExecution;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourceProperty;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.ResourceSelectStrategy;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Server-side resolution for remote lock requests: the admission check (existence/exposure) and
 * resolution through the <b>canonical</b> {@link LockableResourcesManager#getAvailableResources} path -
 * the same one local {@code lock()} uses, with the configured {@code exposeLabel} set as the candidate
 * filter. The server never re-implements {@code lock()} semantics; this class only decides
 * <em>which</em> resources a remote request may see and resolves them, then hands off to the manager.
 *
 * <p>A pure, stateless collaborator over the manager's public API. Every call must be made while holding
 * {@link LockableResourcesManager#syncResources} - the caller acquires it; this class never takes the lock
 * itself.
 */
@Restricted(NoExternalUse.class)
public final class RemoteResolver {

    private final LockableResourcesManager lrm;

    public RemoteResolver(@NonNull LockableResourcesManager lrm) {
        this.lrm = lrm;
    }

    /**
     * Admission check for a remote request: every selector (the main one and each {@code extra}
     * entry) must reference something this remote client could actually lock - a resource that exists and
     * is exposed, or a label with at least one exposed candidate. Exposure means "carries any configured
     * {@code exposeLabel}" (see {@link LockableResourcesManager#getExposeLabels()}). This is the only
     * remote-specific gate; the actual resolution still goes through the canonical
     * {@link LockableResourcesManager#getAvailableResources} path. Must be called under
     * {@link LockableResourcesManager#syncResources}.
     *
     * @return an error code ({@code "UNKNOWN_RESOURCE"} / {@code "UNKNOWN_LABEL"}) or {@code null} when all
     *     selectors are admissible. The caller maps the code to HTTP 404.
     */
    @CheckForNull
    public String validateRemoteSelectors(@NonNull RemoteLockRequest req) {
        Set<String> exposeLabels = lrm.getExposeLabels();
        String main = validateSelector(req.getResource(), req.getLabel(), exposeLabels);
        if (main != null) {
            return main;
        }
        if (req.getExtra() != null) {
            for (RemoteLockRequest.ExtraResource e : req.getExtra()) {
                String err = validateSelector(e.getResource(), e.getLabel(), exposeLabels);
                if (err != null) {
                    return err;
                }
            }
        }
        return null;
    }

    @CheckForNull
    private String validateSelector(
            @CheckForNull String resource, @CheckForNull String label, @NonNull Set<String> exposeLabels) {
        if (resource != null) {
            LockableResource r = lrm.fromName(resource);
            return (r != null && isExposed(r, exposeLabels)) ? null : "UNKNOWN_RESOURCE";
        }
        if (label != null && !label.isEmpty()) {
            return hasExposedCandidate(label, exposeLabels) ? null : "UNKNOWN_LABEL";
        }
        return null; // absent selector (e.g. main when extra-only) - nothing to validate
    }

    /** A resource is exposed to remote clients iff it carries at least one configured exposeLabel (OR). */
    private static boolean isExposed(@NonNull LockableResource r, @NonNull Set<String> exposeLabels) {
        return !exposeLabels.isEmpty() && !Collections.disjoint(r.getLabelsAsList(), exposeLabels);
    }

    private boolean hasExposedCandidate(@NonNull String label, @NonNull Set<String> exposeLabels) {
        if (exposeLabels.isEmpty()) {
            return false; // exposeLabel is opt-in: empty exposes nothing
        }
        for (LockableResource r : lrm.getResources()) {
            List<String> labels = r.getLabelsAsList();
            if (labels.contains(label) && !Collections.disjoint(labels, exposeLabels)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the canonical {@link LockableResourcesStruct} list for a remote request, mirroring
     * {@code LockStep.getResources()} (main selector + each {@code extra} entry). Resources are resolved by
     * name only (no ephemeral creation): admission ({@link #validateRemoteSelectors}) has already confirmed
     * every named selector exists and is exposed, so a remote request never materialises new resources on
     * the server. Must be called under {@link LockableResourcesManager#syncResources}.
     */
    @NonNull
    public List<LockableResourcesStruct> toRemoteStructs(@NonNull RemoteLockRequest req) {
        List<LockableResourcesStruct> structs = new ArrayList<>();
        addRemoteStruct(structs, req.getResource(), req.getLabel(), req.getQuantity());
        if (req.getExtra() != null) {
            for (RemoteLockRequest.ExtraResource e : req.getExtra()) {
                addRemoteStruct(structs, e.getResource(), e.getLabel(), e.getQuantity());
            }
        }
        return structs;
    }

    private void addRemoteStruct(
            List<LockableResourcesStruct> structs,
            @CheckForNull String resource,
            @CheckForNull String label,
            int quantity) {
        boolean hasLabel = label != null && !label.isEmpty();
        if (resource == null && !hasLabel) {
            return; // absent selector (e.g. main when extra-only)
        }
        List<String> names = new ArrayList<>();
        if (resource != null) {
            // No ephemeral creation here: admission (validateRemoteSelectors) guarantees the name exists and
            // is exposed, so a remote request never creates new resources on the server.
            names.add(resource);
        }
        structs.add(new LockableResourcesStruct(names, label, quantity));
    }

    /**
     * Resolves a remote request (given its canonical {@code structs}, from {@link #toRemoteStructs})
     * through the canonical {@link LockableResourcesManager#getAvailableResources} path, applying the
     * configured {@code exposeLabel} set as the candidate-visibility filter (a resource is visible iff it
     * carries any exposeLabel - OR). The filter is a plain {@link java.util.function.Predicate} so the
     * canonical resolver never learns about exposeLabel; the "requested-label AND exposeLabel" intersection
     * is expressed entirely here, leaving local lock()'s single-label matching untouched. Returns the
     * resources to lock (atomically) or {@code null} if not all currently satisfiable (-> QUEUED, like
     * local). Shared by the immediate acquire and queue-promotion paths. Must be called under
     * {@link LockableResourcesManager#syncResources}.
     */
    @CheckForNull
    public List<LockableResource> availableForRemote(
            @NonNull List<LockableResourcesStruct> structs, @NonNull RemoteLockRequest req) {
        if (structs.isEmpty()) {
            return null;
        }
        Set<String> exposeLabels = lrm.getExposeLabels();
        List<LockableResource> available = lrm.getAvailableResources(
                structs, null, parseSelectStrategy(req.getResourceSelectStrategy()), r -> isExposed(r, exposeLabels));
        return (available == null || available.isEmpty()) ? null : available;
    }

    /**
     * Builds {@code lockEnvVars} for an acquired remote lock from the resolved resources, via the shared
     * {@link LockStepExecution#buildLockEnvVars} - identical to local, including resource-property env
     * vars. Returns {@code null} when no {@code variable} was requested.
     */
    @CheckForNull
    public static Map<String, String> remoteLockEnvVars(
            @CheckForNull String variable, @NonNull List<LockableResource> resources) {
        LinkedHashMap<String, List<LockableResourceProperty>> map = new LinkedHashMap<>();
        for (LockableResource r : resources) {
            map.put(r.getName(), r.getProperties());
        }
        return LockStepExecution.buildLockEnvVars(variable, map);
    }

    private static ResourceSelectStrategy parseSelectStrategy(@CheckForNull String strategy) {
        if (strategy != null) {
            try {
                return ResourceSelectStrategy.valueOf(strategy.toUpperCase(Locale.ENGLISH));
            } catch (IllegalArgumentException ignored) {
                // fall through to default
            }
        }
        return ResourceSelectStrategy.SEQUENTIAL;
    }
}
