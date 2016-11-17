package org.jenkins.plugins.lockableresources.resources.LockableResourcesManager

import hudson.security.SecurityRealm
import hudson.markup.MarkupFormatterDescriptor
import hudson.security.AuthorizationStrategy
import jenkins.AgentProtocol
import jenkins.model.GlobalConfiguration
import hudson.Functions
import hudson.model.Descriptor
import org.jenkins.plugins.lockableresources.queue.policy.QueuePolicy
import org.jenkins.plugins.lockableresources.resources.selector.ResourcesSelector

/*
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 */

def f=namespace(lib.FormTagLib)
def l=namespace(lib.LayoutTagLib)
def st=namespace("jelly:stapler")

f.section(title:_("Lockable Resources Manager")) {
    f.entry(title:_("Use fair selection - experimental"), field:"useFairSelection") {
        f.checkbox()
    }
    f.entry(title:_("Display resources list in main view"), field:"showWidget") {
        f.checkbox()
    }
    f.entry(title:_("Display task queue with resources"), field:"showQueue") {
        f.checkbox()
    }
    f.entry(title:_("Default reservation duration (hours)"), field:"defaultReservationHours") {
        f.textbox()
    }
    f.entry(title:_("Max reservation duration (hours)"), field:"maxReservationHours") {
        f.textbox()
    }
    f.dropdownDescriptorSelector(title:_("Queue policy"), field:"queuePolicy", descriptors:QueuePolicy.all())
    f.dropdownDescriptorSelector(title:_("Resources selector"), field:"resourcesSelector", descriptors:ResourcesSelector.all())
    f.entry(title:_("Lockable Resources")) {
        f.repeatable(field:"resources", header:_("Resource"), minimum:0, add:_("Add lockable resource")) {
            table(style: "width:100%") {
                st.include(page:"config.jelly", class:"org.jenkins.plugins.lockableresources.resources.LockableResource")
                f.entry() {
                    div(align:"right") {
                        f.repeatableDeleteButton()
                    }
                }
            }
        }
    }
}
