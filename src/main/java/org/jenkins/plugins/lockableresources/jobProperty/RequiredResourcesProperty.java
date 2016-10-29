/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.jobProperty;

import hudson.Extension;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Job;
import java.util.ArrayList;
import java.util.Collection;
import jenkins.model.OptionalJobProperty;
import org.jenkins.plugins.lockableresources.BackwardCompatibility;
import org.jenkins.plugins.lockableresources.resources.RequiredResources;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.export.Exported;

@Extension
public class RequiredResourcesProperty extends OptionalJobProperty<Job<?, ?>> {
    /** For backward compatibility. Please use {@link #requiredResourcesList} */
    @Deprecated
    private transient final String resourceNames = null;
    /** For backward compatibility. Please use {@link #requiredResourcesList} */
    @Deprecated
    private transient final String resourceNamesVar = null;
    /** For backward compatibility. Please use {@link #requiredResourcesList} */
    @Deprecated
    private transient final String resourceNumber = null;
    /** For backward compatibility. Please use {@link #requiredResourcesList} */
    @Deprecated
    private transient final String labelName = null;
    @Exported
    protected Collection<RequiredResources> requiredResourcesList = new ArrayList<>();
    @Exported
    protected String variableName = null;

    /**
     * Backward compatibility
     */
    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void initBackwardCompatibility() {
        BackwardCompatibility.init();
    }

    @DataBoundConstructor
    public RequiredResourcesProperty() {
        super();
    }

    public RequiredResourcesProperty(Collection<RequiredResources> requiredResourcesList) {
        super();
        this.requiredResourcesList = requiredResourcesList;
    }

    @Exported
    public Collection<RequiredResources> getRequiredResourcesList() {
        return requiredResourcesList;
    }

    @DataBoundSetter
    public void setRequiredResourcesList(Collection<RequiredResources> requiredResourcesList) {
        this.requiredResourcesList = requiredResourcesList;
    }

    @Exported
    public String getVariableName() {
        return variableName;
    }

    @DataBoundSetter
    public void setVariableName(String variableName) {
        this.variableName = variableName;
    }

    /**
     * Magically called when imported from XML file
     * Manage backward compatibility
     *
     * @return myself
     */
    public Object readResolve() {
        if(resourceNames != null || labelName != null) {
            int n = 0;
            if(resourceNumber != null) {
                try {
                    n = Integer.parseInt(resourceNumber);
                } catch(NumberFormatException e) {
                }
            }
            requiredResourcesList = new ArrayList<>();
            requiredResourcesList.add(new RequiredResources(Util.fixNull(resourceNames), Util.fixNull(labelName), n));
        }
        if(resourceNamesVar != null) {
            variableName = resourceNamesVar;
        }
        return this;
    }

    @Extension
    public static class DescriptorImpl extends OptionalJobPropertyDescriptor {
        @Override
        public String getDisplayName() {
            return "Required Lockable Resources List";
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return Job.class.isAssignableFrom(jobType);
        }
    }
}
