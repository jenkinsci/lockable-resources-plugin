/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import java.io.Serializable;
import java.util.Collections;
import java.util.Set;
import java.util.logging.Logger;
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

/**
 * Pipeline step to update the definition of a lockable resource.
 *
 * <p>This step allows pipelines to:
 * <ul>
 *   <li>Create new resources</li>
 *   <li>Delete existing resources</li>
 *   <li>Add, remove, or set labels on resources</li>
 *   <li>Set notes on resources</li>
 * </ul>
 */
public class UpdateLockStep extends Step implements Serializable {

    private static final Logger LOG = Logger.getLogger(UpdateLockStep.class.getName());
    private static final long serialVersionUID = -7955849755535282258L;

    @CheckForNull
    private String resource = null;

    @CheckForNull
    private String addLabels = null;

    @CheckForNull
    private String setLabels = null;

    @CheckForNull
    private String removeLabels = null;

    @CheckForNull
    private String setNote = null;

    private boolean createResource = false;
    private boolean deleteResource = false;

    @DataBoundConstructor
    public UpdateLockStep() {
        // default constructor
    }

    @CheckForNull
    public String getResource() {
        return resource;
    }

    @DataBoundSetter
    public void setResource(String resource) {
        if (resource != null && !resource.trim().isEmpty()) {
            if (!resource.equals(resource.trim())) {
                LOG.warning("The provided 'resource' should not start or end with spaces.");
            }
            this.resource = resource.trim();
        }
    }

    @CheckForNull
    public String getAddLabels() {
        return addLabels;
    }

    @DataBoundSetter
    public void setAddLabels(String addLabels) {
        addLabels = Util.fixEmptyAndTrim(addLabels);
        if (addLabels != null) {
            this.addLabels = addLabels;
        }
    }

    @CheckForNull
    public String getSetLabels() {
        return setLabels;
    }

    @DataBoundSetter
    public void setSetLabels(String setLabels) {
        setLabels = Util.fixEmptyAndTrim(setLabels);
        if (setLabels != null) {
            this.setLabels = setLabels;
        }
    }

    @CheckForNull
    public String getRemoveLabels() {
        return removeLabels;
    }

    @DataBoundSetter
    public void setRemoveLabels(String removeLabels) {
        removeLabels = Util.fixEmptyAndTrim(removeLabels);
        if (removeLabels != null) {
            this.removeLabels = removeLabels;
        }
    }

    @CheckForNull
    public String getSetNote() {
        return setNote;
    }

    @DataBoundSetter
    public void setSetNote(String setNote) {
        setNote = Util.fixEmptyAndTrim(setNote);
        if (setNote != null) {
            this.setNote = setNote;
        }
    }

    public boolean isCreateResource() {
        return createResource;
    }

    @DataBoundSetter
    public void setCreateResource(boolean createResource) {
        this.createResource = createResource;
    }

    public boolean isDeleteResource() {
        return deleteResource;
    }

    @DataBoundSetter
    public void setDeleteResource(boolean deleteResource) {
        this.deleteResource = deleteResource;
    }

    /**
     * Validates the step configuration.
     *
     * @throws IllegalArgumentException if the configuration is invalid
     */
    public void validate() {
        if (Util.fixEmptyAndTrim(resource) == null) {
            throw new IllegalArgumentException(Messages.UpdateLockStep_error_resourceRequired());
        }
        if (deleteResource && createResource) {
            throw new IllegalArgumentException(Messages.UpdateLockStep_error_deleteAndCreateConflict());
        }
        if (deleteResource && (addLabels != null || setLabels != null || removeLabels != null || setNote != null)) {
            throw new IllegalArgumentException(Messages.UpdateLockStep_error_deleteWithOtherOptions());
        }
        if (setLabels != null && (addLabels != null || removeLabels != null)) {
            throw new IllegalArgumentException(Messages.UpdateLockStep_error_setLabelsConflict());
        }
    }

    @Override
    public StepExecution start(StepContext context) {
        return new UpdateLockStepExecution(this, context);
    }

    @Override
    public String toString() {
        StringBuilder sb =
                new StringBuilder("UpdateLockStep{resource='").append(resource).append("'");
        if (createResource) sb.append(", createResource=true");
        if (deleteResource) sb.append(", deleteResource=true");
        if (setLabels != null) sb.append(", setLabels='").append(setLabels).append("'");
        if (addLabels != null) sb.append(", addLabels='").append(addLabels).append("'");
        if (removeLabels != null)
            sb.append(", removeLabels='").append(removeLabels).append("'");
        if (setNote != null) sb.append(", setNote='").append(setNote).append("'");
        sb.append("}");
        return sb.toString();
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "updateLock";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.UpdateLockStep_displayName();
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return false;
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            return Collections.singleton(TaskListener.class);
        }

        /**
         * Provides auto-completion for resource names.
         */
        @RequirePOST
        public AutoCompletionCandidates doAutoCompleteResource(
                @QueryParameter String value, @AncestorInPath Item item) {
            return RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames(value, item);
        }

        /**
         * Validates the resource name.
         */
        @RequirePOST
        public FormValidation doCheckResource(@QueryParameter String value, @AncestorInPath Item item) {
            if (item != null) {
                item.checkPermission(Item.CONFIGURE);
            } else {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            }
            value = Util.fixEmptyAndTrim(value);
            if (value == null) {
                return FormValidation.error(Messages.UpdateLockStep_error_resourceRequired());
            }
            return FormValidation.ok();
        }

        /**
         * Validates addLabels option - cannot be used with setLabels.
         */
        @RequirePOST
        public FormValidation doCheckAddLabels(
                @QueryParameter String value, @QueryParameter String setLabels, @AncestorInPath Item item) {
            if (item != null) {
                item.checkPermission(Item.CONFIGURE);
            } else {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            }
            return doCheckLabelOperation(value, setLabels);
        }

        /**
         * Validates removeLabels option - cannot be used with setLabels.
         */
        @RequirePOST
        public FormValidation doCheckRemoveLabels(
                @QueryParameter String value, @QueryParameter String setLabels, @AncestorInPath Item item) {
            if (item != null) {
                item.checkPermission(Item.CONFIGURE);
            } else {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            }
            return doCheckLabelOperation(value, setLabels);
        }

        private FormValidation doCheckLabelOperation(String value, String setLabels) {
            if (Util.fixEmptyAndTrim(value) != null && Util.fixEmptyAndTrim(setLabels) != null) {
                return FormValidation.error(Messages.UpdateLockStep_error_setLabelsConflict());
            }
            return FormValidation.ok();
        }

        /**
         * Validates deleteResource option - cannot be combined with other modify options.
         */
        @RequirePOST
        public FormValidation doCheckDeleteResource(
                @QueryParameter boolean value,
                @QueryParameter String setLabels,
                @QueryParameter String addLabels,
                @QueryParameter String removeLabels,
                @QueryParameter String setNote,
                @QueryParameter boolean createResource,
                @AncestorInPath Item item) {
            if (item != null) {
                item.checkPermission(Item.CONFIGURE);
            } else {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            }
            if (!value) {
                return FormValidation.ok();
            }
            if (createResource) {
                return FormValidation.error(Messages.UpdateLockStep_error_deleteAndCreateConflict());
            }
            if (Util.fixEmptyAndTrim(setLabels) != null
                    || Util.fixEmptyAndTrim(addLabels) != null
                    || Util.fixEmptyAndTrim(removeLabels) != null
                    || Util.fixEmptyAndTrim(setNote) != null) {
                return FormValidation.error(Messages.UpdateLockStep_error_deleteWithOtherOptions());
            }
            return FormValidation.ok();
        }
    }
}
