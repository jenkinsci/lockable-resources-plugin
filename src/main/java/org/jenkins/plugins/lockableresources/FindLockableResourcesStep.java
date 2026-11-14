/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Extension;
import hudson.Util;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Pipeline step that lists the local lockable resources matching a label, for example to build the {@code order}
 * list of the {@code lock} step. It is read-only: it neither locks nor changes anything.
 */
public class FindLockableResourcesStep extends Step implements Serializable {

    private static final long serialVersionUID = 1L;

    @CheckForNull
    private final String label;

    @CheckForNull
    private String group;

    @DataBoundConstructor
    public FindLockableResourcesStep(@CheckForNull String label) {
        this.label = Util.fixEmptyAndTrim(label);
    }

    @CheckForNull
    public String getLabel() {
        return label;
    }

    /** The group to filter by, or {@code null} to return matching resources of every group. */
    @CheckForNull
    public String getGroup() {
        return group;
    }

    @DataBoundSetter
    public void setGroup(@CheckForNull String group) {
        this.group = Util.fixEmptyAndTrim(group);
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(label, group, context);
    }

    /** Same as {@link #find(String, String)} without a group filter. */
    @NonNull
    static List<Map<String, Object>> find(@NonNull String label) {
        return find(label, null);
    }

    /**
     * Returns one map per local resource matching {@code label} (same label expression syntax as {@code lock}), with
     * the keys {@code name}, {@code labels}, {@code group}, {@code description} and {@code note}. When {@code group}
     * is not blank, only resources of that group are returned.
     */
    @NonNull
    static List<Map<String, Object>> find(@NonNull String label, @CheckForNull String group) {
        String requestedGroup = Util.fixEmptyAndTrim(group) == null ? null : LockableResource.normalizeGroup(group);
        List<Map<String, Object>> found = new ArrayList<>();
        for (LockableResource resource : LockableResourcesManager.get().getResourcesWithLabel(label)) {
            if (requestedGroup != null && !requestedGroup.equals(resource.getGroup())) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", resource.getName());
            entry.put("labels", new ArrayList<>(resource.getLabelsAsList()));
            entry.put("group", resource.getGroup());
            entry.put("description", resource.getDescription());
            entry.put("note", resource.getNote());
            found.add(entry);
        }
        return found;
    }

    private static final class Execution extends SynchronousNonBlockingStepExecution<List<Map<String, Object>>> {

        private static final long serialVersionUID = 1L;

        @CheckForNull
        private final String label;

        @CheckForNull
        private final String group;

        Execution(@CheckForNull String label, @CheckForNull String group, StepContext context) {
            super(context);
            this.label = label;
            this.group = group;
        }

        @Override
        protected List<Map<String, Object>> run() throws Exception {
            if (label == null) {
                throw new AbortException(Messages.FindLockableResourcesStep_error_labelRequired());
            }
            return find(label, group);
        }
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "findLockableResources";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.FindLockableResourcesStep_displayName();
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            return Collections.emptySet();
        }
    }
}
