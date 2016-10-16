/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.jobParameter;

import hudson.Extension;
import hudson.model.ParameterDefinition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import net.sf.json.JSONObject;
import org.jenkins.plugins.lockableresources.resources.ResourceCapability;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

public class LockableResourcesParameterDefinition extends ParameterDefinition {
    private static final Logger LOGGER = Logger.getLogger(LockableResourcesParameterDefinition.class.getName());
    @Exported
    protected Collection<ResourceCapability> selectedCapabilities;
    @Exported
    protected Collection<ResourceCapability> neededCapabilities;
    @Exported
    protected Collection<ResourceCapability> prohibitedCapabilities;
    @Exported
    protected Boolean onlyResourceNames;

    @DataBoundConstructor
    public LockableResourcesParameterDefinition(String name, boolean onlyResourceNames, Collection<ResourceCapability> selectedCapabilities, Collection<ResourceCapability> neededCapabilities, Collection<ResourceCapability> prohibitedCapabilities, String description) {
        super(name, description);
        this.onlyResourceNames = onlyResourceNames;
        this.selectedCapabilities = (selectedCapabilities == null) ? new ArrayList<ResourceCapability>() : selectedCapabilities;
        this.neededCapabilities = (neededCapabilities == null) ? new ArrayList<ResourceCapability>() : neededCapabilities;
        this.prohibitedCapabilities = (prohibitedCapabilities == null) ? new ArrayList<ResourceCapability>() : prohibitedCapabilities;
    }

    @Exported
    public Boolean getOnlyResourceNames() {
        return onlyResourceNames;
    }

    @DataBoundSetter
    public void setOnlyResourceNames(Boolean onlyResourceNames) {
        this.onlyResourceNames = onlyResourceNames;
    }

    @Exported
    public Collection<ResourceCapability> getSelectedCapabilities() {
        return selectedCapabilities;
    }

    @DataBoundSetter
    public void setSelectedCapabilities(Collection<ResourceCapability> selectedCapabilities) {
        this.selectedCapabilities = selectedCapabilities;
    }

    @Exported
    public Collection<ResourceCapability> getNeededCapabilities() {
        return neededCapabilities;
    }

    @DataBoundSetter
    public void setNeededCapabilities(Collection<ResourceCapability> neededCapabilities) {
        this.neededCapabilities = neededCapabilities;
    }

    @Exported
    public Collection<ResourceCapability> getProhibitedCapabilities() {
        return prohibitedCapabilities;
    }

    @DataBoundSetter
    public void setProhibitedCapabilities(Collection<ResourceCapability> prohibitedCapabilities) {
        this.prohibitedCapabilities = prohibitedCapabilities;
    }

    public String getNeededLabels() {
        return ResourceCapability.createLabel(neededCapabilities);
    }

    public String getProhibitedLabels() {
        return ResourceCapability.createLabel(prohibitedCapabilities);
    }

    @Override
    public LockableResourcesParameterValue getDefaultParameterValue() {
        return new LockableResourcesParameterValue(getName(), onlyResourceNames, selectedCapabilities, neededCapabilities, getDescription());
    }

    @Override
    public LockableResourcesParameterValue createValue(StaplerRequest req) {
        LOGGER.fine("*** createValue: StaplerRequest ***");
        try {
            JSONObject jo = req.getSubmittedForm();
            return createValue(req, jo);
        } catch(ServletException ex) {
            throw new IllegalArgumentException("Illegal parameters for " + getName());
        }
    }

    @Override
    public LockableResourcesParameterValue createValue(StaplerRequest req, JSONObject jo) {
        LOGGER.fine("*** createValue: bindJSON ***");
        for(Object v : jo.entrySet()) {
            if(v instanceof Map.Entry) {
                Map.Entry e = (Map.Entry) v;
                LOGGER.fine("'" + e.getKey().toString() + "' = '" + e.getValue().toString() + "'");
            } else {
                LOGGER.fine("Unknown entrey type: " + v.getClass().getName());
            }
        }
        LockableResourcesParameterValue value = req.bindJSON(LockableResourcesParameterValue.class, jo);
        LOGGER.fine("*** value." + value.getName() + "=" + value.getValue().toString() + " ***");
        value.setDescription(getDescription());
        return value;
    }

    /* @Override
     * public LockableResourcesParameterValue createValue(CLICommand command, String value) throws IOException, InterruptedException {
     * throw new IllegalArgumentException("Not supported");
     * } */
    @Extension
    public static class DescriptorImpl extends ParameterDescriptor {
        @Override
        public String getDisplayName() {
            return "Lockable resources selection";
        }
    }
}
