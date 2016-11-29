/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction

import org.jenkins.plugins.lockableresources.LockableResources

def l = namespace(lib.LayoutTagLib.class)
def st = namespace("jelly:stapler")

l.layout(title: my.displayName) {
    l.main_panel {
        h1 _("Lockable Resources")
        if(LockableResources.canRead()) {
            table(class:"pane", style:"width: 80%;") {
                tr {
                    td(class:"pane-header", _("Resource"))
                    td(class:"pane-header", _("Status"))
                    td(class:"pane-header", _("Capabilities"))
                    td(class:"pane-header", _("Action"))
                }
                compact = false
                for(resource in my.resources) {
                    st.include(it:resource, page:"status.groovy")
                }
            }
            h3 _("Capabilities")
            table(class:"pane", style:"width: 50%;") {
                tr {
                    td(class:"pane-header", _("Capability"))
                    td(class:"pane-header", _("Free resources"))
                }
                for(label in my.getAllLabels()) {
                    tr {
                        def nb = my.getFreeResourceAmount(label)
                        if(nb <= 0) {
                            td(class:"pane", style:"color: red;", label)
                            td(class:"pane", style:"color: red;", nb)
                        } else if(nb == 1) {
                            td(class:"pane", style:"color: darkorange;", label)
                            td(class:"pane", style:"color: darkorange;", nb)
                        } else {
                            td(class:"pane", style:"color: green;", label)
                            td(class:"pane", style:"color: green;", nb)
                        }
                    }
                }
            }
        }
    }
}
