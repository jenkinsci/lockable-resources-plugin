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
import hudson.util.XStream2;
import java.util.Collection;
import jenkins.model.Jenkins;
import jenkins.model.OptionalJobProperty;
import org.jenkins.plugins.lockableresources.resources.RequiredResources;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.export.Exported;

public class RequiredResourcesProperty extends OptionalJobProperty<Job<?, ?>> {
    /** For backward compatibility. Please use {@link #requiredResourcesList} */
    @Deprecated
    private transient String resourceNames;
    /** For backward compatibility. Please use {@link #requiredResourcesList} */
    @Deprecated
    private transient String resourceNamesVar;
    /** For backward compatibility. Please use {@link #requiredResourcesList} */
    @Deprecated
    private transient String resourceNumber;
    /** For backward compatibility. Please use {@link #requiredResourcesList} */
    @Deprecated
    private transient String labelName;
    @Exported
    protected Collection<RequiredResources> requiredResourcesList;

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

    /**
     * Magicaly called after restoring persistance
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
            requiredResourcesList.add(new RequiredResources(Util.fixNull(resourceNames), Util.fixNull(labelName), n, Util.fixNull(resourceNamesVar)));
        }
        return this;
    }

    @Extension
    public static class DescriptorImpl extends OptionalJobPropertyDescriptor {
        private static final XStream2 XSTREAM2 = new XStream2();

        /**
         * Add backward compatibility
         */
        @Initializer(before = InitMilestone.PLUGINS_STARTED)
        public static void addAliases() {
            Jenkins.XSTREAM2.addCompatibilityAlias("org.jenkins.plugins.lockableresources.RequiredResourcesProperty", RequiredResourcesProperty.class);
        }

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
