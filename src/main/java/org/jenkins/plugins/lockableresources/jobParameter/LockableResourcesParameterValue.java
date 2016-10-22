/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.jobParameter;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractBuild;
import hudson.model.ParameterValue;
import hudson.model.Run;
import hudson.util.VariableResolver;
import java.util.LinkedHashSet;
import java.util.TreeSet;
import org.jenkins.plugins.lockableresources.BackwardCompatibility;
import org.jenkins.plugins.lockableresources.resources.ResourceCapability;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.export.Exported;

public class LockableResourcesParameterValue extends ParameterValue implements Comparable<LockableResourcesParameterValue> {
    private static final long serialVersionUID = 1L;
    @Exported
    @SuppressFBWarnings(value = "SE_BAD_FIELD", justification = "Jenkins can serialize LinkedHashSet")
    protected LinkedHashSet<ResourceCapability> selectedCapabilities;
    @Exported
    @SuppressFBWarnings(value = "SE_BAD_FIELD", justification = "Jenkins can serialize LinkedHashSet")
    protected LinkedHashSet<ResourceCapability> neededCapabilities;
    @Exported
    protected Boolean onlyResourceNames;

    /**
     * Backward compatibility
     */
    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void initBackwardCompatibility() {
        BackwardCompatibility.init();
    }

    @DataBoundConstructor
    public LockableResourcesParameterValue(String name, String description) {
        this(name, description, null, null, null);
    }

    public LockableResourcesParameterValue(String name, String description, Boolean onlyResourceNames, LinkedHashSet<ResourceCapability> selectedCapabilities, LinkedHashSet<ResourceCapability> neededCapabilities) {
        super(name, description);
        this.selectedCapabilities = (selectedCapabilities == null) ? new LinkedHashSet<ResourceCapability>() : selectedCapabilities;
        this.neededCapabilities = (neededCapabilities == null) ? new LinkedHashSet<ResourceCapability>() : neededCapabilities;
        this.onlyResourceNames = onlyResourceNames;
    }

    @Exported
    public LinkedHashSet<ResourceCapability> getSelectedCapabilities() {
        return selectedCapabilities;
    }

    @DataBoundSetter
    public void setSelectedCapabilities(LinkedHashSet<ResourceCapability> selectedCapabilities) {
        this.selectedCapabilities = selectedCapabilities;
    }

    @Exported
    public LinkedHashSet<ResourceCapability> getNeededCapabilities() {
        return neededCapabilities;
    }

    @DataBoundSetter
    public void setNeededCapabilities(LinkedHashSet<ResourceCapability> neededCapabilities) {
        this.neededCapabilities = neededCapabilities;
    }

    @Exported
    public Boolean getOnlyResourceNames() {
        return onlyResourceNames;
    }

    @DataBoundSetter
    public void setNeededCapabilities(Boolean onlyResourceNames) {
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
        if(this == obj) {
            return true;
        }
        if(!super.equals(obj)) {
            return false;
        }
        if(getClass() != obj.getClass()) {
            return false;
        }
        LockableResourcesParameterValue other = (LockableResourcesParameterValue) obj;
        return getEnvString().equals(other.getEnvString());
    }
}
