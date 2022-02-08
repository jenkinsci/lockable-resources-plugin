package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import java.io.Serializable;
import org.kohsuke.stapler.QueryParameter;

public class UpdateLockStepResource extends AbstractDescribableImpl<UpdateLockStepResource> implements Serializable {

  private static final long serialVersionUID = -3689811142454137183L;


  @Extension
  public static class DescriptorImpl extends Descriptor<UpdateLockStepResource> {

    @NonNull
    @Override
    public String getDisplayName() {
      return "Resource Update";
    }

    public AutoCompletionCandidates doAutoCompleteResource(@QueryParameter String value) {
      return RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames(value);
    }

    public static FormValidation doCheckLabelOperations(String value, String setLabels) {
      String updateLabel = Util.fixEmpty(value);
      setLabels = Util.fixEmpty(setLabels);

      if (setLabels != null && updateLabel != null) {
        return FormValidation.error("Cannot set and update labels at the same time.");
      }
      return FormValidation.ok();
    }

    public static FormValidation doCheckResource(@QueryParameter String value) {
      String resourceName = Util.fixEmpty(value);
      if (resourceName == null) {
        return FormValidation.error("Resource name cannot be empty.");
      }
      return FormValidation.ok();
    }

    public static FormValidation doCheckDelete(boolean value, String setLabels, String addLabels, String removeLabels, String setNote, boolean createResource) {
      if (!value) {
        return FormValidation.ok();
      }

      if (createResource) {
        return FormValidation.error("Cannot create and delete a resource.");
      }

      if (Util.fixEmpty(setLabels) != null || Util.fixEmpty(addLabels) != null || Util.fixEmpty(removeLabels) != null || Util.fixEmpty(setNote) != null)  {
        return FormValidation.error("Cannot update and delete a resource.");
      }
      return FormValidation.ok();
    }
  }
}
