/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.model.ParameterValue;
import hudson.model.Run;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.util.VariableResolver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.TreeSet;

public class LockableResourcesParameterValue extends ParameterValue implements Comparable<LockableResourcesParameterValue> {
    private Collection<ResourceCapability> selectedCapabilities;
    private Collection<ResourceCapability> neededCapabilities;
    private Boolean onlyResourceNames;

    @DataBoundConstructor
    public LockableResourcesParameterValue(String name, Boolean onlyResourceNames, Collection<ResourceCapability> selectedCapabilities, Collection<ResourceCapability> neededCapabilities) {
        this(name, onlyResourceNames, selectedCapabilities, neededCapabilities, null);
    }
    
    public LockableResourcesParameterValue(String name, Boolean onlyResourceNames, Collection<ResourceCapability> selectedCapabilities, Collection<ResourceCapability> neededCapabilities, String description) {
        super(name, description);
		this.selectedCapabilities = (selectedCapabilities == null) ? new ArrayList<ResourceCapability>() : selectedCapabilities;
		this.neededCapabilities = (neededCapabilities == null) ? new ArrayList<ResourceCapability>() : neededCapabilities;
        this.onlyResourceNames = onlyResourceNames;
    }

    @Override
    public int compareTo(LockableResourcesParameterValue o) {
        return name.compareTo(o.name);
    }

    @Override
    public LockableResourcesParameterValue getValue() {
        return this;
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
	
    public Boolean getOnlyResourceNames() {
        return onlyResourceNames;
    }
    public void setNeededCapabilities(Boolean onlyResourceNames) {
        this.onlyResourceNames = onlyResourceNames;
    }

    public String getEnvString() {
        TreeSet<ResourceCapability> capabilities = new TreeSet<>();
        if(selectedCapabilities != null) {
            capabilities.addAll(selectedCapabilities);
        }
		if(!onlyResourceNames) {
			if(neededCapabilities != null) {
				capabilities.addAll(neededCapabilities);
			}
		}
        return ResourceCapability.createLabel(capabilities);
    }

    @Override
    public void buildEnvironment(Run<?, ?> build, EnvVars env) {
        env.put(name, getEnvString());
    }

    @Override
    public VariableResolver<String> createVariableResolver(AbstractBuild<?, ?> build) {
        return new VariableResolver<String>() {
            @Override
            public String resolve(String name) {
                return LockableResourcesParameterValue.this.name.equals(name) ? getEnvString() : null;
            }
        };
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + getEnvString().hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "(LockableResourcesParameterValue) " + getShortDescription();
    }

    @Override
    public String getShortDescription() {
        return name + "='" + getEnvString() + "'";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!super.equals(obj)) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        LockableResourcesParameterValue other = (LockableResourcesParameterValue) obj;
        return getEnvString().equals(other.getEnvString());
    }
}
