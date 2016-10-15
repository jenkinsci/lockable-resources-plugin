/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import hudson.Extension;
import hudson.cli.CLICommand;
import hudson.model.ParameterDefinition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.logging.Logger;
import javax.servlet.ServletException;

import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

public class LockableResourcesParameterDefinition extends ParameterDefinition {
    private Collection<ResourceCapability> selectedCapabilities;
    private Collection<ResourceCapability> neededCapabilities;
    private Collection<ResourceCapability> prohibitedCapabilities;
    private Boolean onlyResourceNames;
    private static final Logger LOGGER = Logger.getLogger(LockableResourcesParameterDefinition.class.getName());

    @DataBoundConstructor
    public LockableResourcesParameterDefinition(String name, Boolean onlyResourceNames, Collection<ResourceCapability> selectedCapabilities, Collection<ResourceCapability> neededCapabilities, Collection<ResourceCapability> prohibitedCapabilities, String description) {
        super(name, description);
        this.onlyResourceNames = onlyResourceNames;
		this.selectedCapabilities = (selectedCapabilities == null) ? new ArrayList<ResourceCapability>() : selectedCapabilities;
		this.neededCapabilities = (neededCapabilities == null) ? new ArrayList<ResourceCapability>() : neededCapabilities;
		this.prohibitedCapabilities = (prohibitedCapabilities == null) ? new ArrayList<ResourceCapability>() : prohibitedCapabilities;
    }
    
    public Boolean getOnlyResourceNames() {
        return onlyResourceNames;
    }
    public void setOnlyResourceNames(Boolean onlyResourceNames) {
        this.onlyResourceNames = onlyResourceNames;
    }
    public Collection<ResourceCapability> getSelectedCapabilities() {
        return selectedCapabilities;
    }
    public void setSelectedCapabilities(Collection<ResourceCapability> selectedCapabilities) {
        this.selectedCapabilities = selectedCapabilities;
    }
    public Collection<ResourceCapability> getNeededCapabilities() {
        return neededCapabilities;
    }
    public void setNeededCapabilities(Collection<ResourceCapability> neededCapabilities) {
        this.neededCapabilities = neededCapabilities;
    }
    public Collection<ResourceCapability> getProhibitedCapabilities() {
        return prohibitedCapabilities;
    }
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
        } catch (ServletException ex) {
            throw new IllegalArgumentException("Illegal parameters for " + getName());
        }
    }
    
    @Override
    public LockableResourcesParameterValue createValue(StaplerRequest req, JSONObject jo) {
        LOGGER.fine("*** createValue: bindJSON ***");
        for(Object v: jo.entrySet()) {
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

    @Override
    public LockableResourcesParameterValue createValue(CLICommand command, String value) throws IOException, InterruptedException {
        throw new IllegalArgumentException("Not supported");
    }
    
    @Extension
    public static class DescriptorImpl extends ParameterDescriptor {
        @Override
        public String getDisplayName() {
            return "Lockable resources selection";
        }
    }
}
