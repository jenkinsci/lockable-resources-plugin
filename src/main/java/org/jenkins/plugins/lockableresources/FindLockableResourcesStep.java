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

/**
 * Pipeline step that lists the local lockable resources matching a label, for example to build the {@code order}
 * list of the {@code lock} step. It is read-only: it neither locks nor changes anything.
 */
public class FindLockableResourcesStep extends Step implements Serializable {

    private static final long serialVersionUID = 1L;

    @CheckForNull
    private final String label;

    @DataBoundConstructor
    public FindLockableResourcesStep(@CheckForNull String label) {
        this.label = Util.fixEmptyAndTrim(label);
    }

    @CheckForNull
    public String getLabel() {
        return label;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(label, context);
    }

    /**
     * Returns one map per local resource matching {@code label} (same label expression syntax as {@code lock}), with
     * the keys {@code name}, {@code labels}, {@code description} and {@code note}.
     */
    @NonNull
    static List<Map<String, Object>> find(@NonNull String label) {
        List<Map<String, Object>> found = new ArrayList<>();
        for (LockableResource resource : LockableResourcesManager.get().getResourcesWithLabel(label)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", resource.getName());
            entry.put("labels", new ArrayList<>(resource.getLabelsAsList()));
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

        Execution(@CheckForNull String label, StepContext context) {
            super(context);
            this.label = label;
        }

        @Override
        protected List<Map<String, Object>> run() throws Exception {
            if (label == null) {
                throw new AbortException(Messages.FindLockableResourcesStep_error_labelRequired());
            }
            return find(label);
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
