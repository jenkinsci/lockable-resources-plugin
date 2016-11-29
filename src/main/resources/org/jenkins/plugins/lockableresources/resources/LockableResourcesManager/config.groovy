/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.resources.LockableResourcesManager

import org.jenkins.plugins.lockableresources.queue.policy.QueuePolicy
import org.jenkins.plugins.lockableresources.resources.selector.ResourcesSelector

def f = namespace(lib.FormTagLib.class)
def st = namespace("jelly:stapler")

f.section(title:_("Lockable Resources Manager")) {
    f.entry(title:_("Display resources list in main view")) {
        f.checkbox(field:"showWidget")
    }
    f.entry(title:_("Display task queue with resources")) {
        f.checkbox(field:"showQueue")
    }
    f.entry(title:_("Default reservation duration (hours)")) {
        f.textbox(field:"defaultReservationHours")
    }
    f.entry(title:_("Max reservation duration (hours)")) {
        f.textbox(field:"maxReservationHours")
    }
    f.dropdownDescriptorSelector(title:_("Queue policy"), field:"queuePolicy", descriptors:QueuePolicy.all())
    f.dropdownDescriptorSelector(title:_("Resources selector"), field:"resourcesSelector", descriptors:ResourcesSelector.all())
    f.entry(title:_("Lockable Resources")) {
        f.repeatable(field:"resources", header:_("Resource"), minimum:0, add:_("Add lockable resource")) {
            table(style: "width:100%") {
                st.include(page:"config.groovy", class:"org.jenkins.plugins.lockableresources.resources.LockableResource")
                f.entry() {
                    div(align:"right") {
                        f.repeatableDeleteButton()
                    }
                }
            }
        }
    }
}
