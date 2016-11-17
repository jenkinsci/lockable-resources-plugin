/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2016, Florian Hug. All rights reserved.             *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import com.google.common.collect.Lists;
import hudson.model.Items;
import hudson.model.Run;
import hudson.model.UpdateCenter;
import hudson.model.User;
import hudson.util.XStream2;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.jobParameter.LockableResourcesParameterDefinition;
import org.jenkins.plugins.lockableresources.jobParameter.LockableResourcesParameterValue;
import org.jenkins.plugins.lockableresources.jobProperty.RequiredResourcesProperty;
import org.jenkins.plugins.lockableresources.resources.ResourceCapability;
import org.jenkins.plugins.lockableresources.step.LockStep;
import org.jenkins.plugins.lockableresources.step.LockStepExecution;

public class BackwardCompatibility {
    public static void init() {
        for(XStream2 xstream2 : Lists.newArrayList(Jenkins.XSTREAM2, Run.XSTREAM2, UpdateCenter.XSTREAM, User.XSTREAM, Items.XSTREAM2)) {
            xstream2.addCompatibilityAlias("org.jenkins.plugins.lockableresources.RequiredResourcesProperty", RequiredResourcesProperty.class);
            xstream2.addCompatibilityAlias("org.jenkins.plugins.lockableresources.LockableResource", org.jenkins.plugins.lockableresources.resources.LockableResource.class);
            xstream2.addCompatibilityAlias("org.jenkins.plugins.lockableresources.LockableResourcesManager", org.jenkins.plugins.lockableresources.resources.LockableResourcesManager.class);
            xstream2.addCompatibilityAlias("org.jenkins.plugins.lockableresources.LockStep", LockStep.class);
            xstream2.addCompatibilityAlias("org.jenkins.plugins.lockableresources.LockStepExecution", LockStepExecution.class);
            xstream2.addCompatibilityAlias("org.jenkins.plugins.lockableresources.ResourceCapability", ResourceCapability.class);
            xstream2.addCompatibilityAlias("org.jenkins.plugins.lockableresources.LockableResourcesParameterDefinition", LockableResourcesParameterDefinition.class);
            xstream2.addCompatibilityAlias("org.jenkins.plugins.lockableresources.LockableResourcesParameterValue", LockableResourcesParameterValue.class);
        }
    }

    private BackwardCompatibility() {
    }
}
