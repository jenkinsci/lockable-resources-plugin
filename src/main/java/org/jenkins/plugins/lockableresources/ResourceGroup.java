package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import java.io.Serializable;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.interceptor.RequirePOST;

/** Global group metadata; resources reference their group by name.
 * @since TODO
 */
@ExportedBean
public class ResourceGroup extends AbstractDescribableImpl<ResourceGroup> implements Serializable {
    private static final long serialVersionUID = 1L;

    /** @since TODO */
    public static final String DEFAULT_NAME = "common";

    private final String name;
    private String description = "";

    @DataBoundConstructor
    public ResourceGroup(String name) {
        String normalized = Util.fixEmptyAndTrim(name);
        if (normalized == null) {
            throw new IllegalArgumentException(Messages.error_groupNameRequired());
        }
        this.name = normalized;
    }

    @Exported
    public String getName() {
        return name;
    }

    @Exported
    public String getDescription() {
        return description;
    }

    @DataBoundSetter
    public void setDescription(@Nullable String description) {
        this.description = Util.fixNull(description);
    }

    @Extension
    @Restricted(NoExternalUse.class)
    public static class DescriptorImpl extends Descriptor<ResourceGroup> {
        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.ResourceGroup_displayName();
        }

        @RequirePOST
        public FormValidation doCheckName(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return Util.fixEmptyAndTrim(value) == null
                    ? FormValidation.error(Messages.error_groupNameRequired())
                    : FormValidation.ok();
        }
    }
}
