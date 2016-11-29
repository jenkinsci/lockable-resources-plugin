/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                     *
 *                                                                         *
 * Dynamic resources management by Darius Mihai (mihai_darius22@yahoo.com  *
 * Copyright (C) 2015 Freescale Semiconductor, Inc.                        *
 *                                                                         *
 * This file is part of the Jenkins Lockable Resources Plugin and is       *
 * published under the MIT license.                                        *
 *                                                                         *
 * See the "LICENSE.txt" file for more information.                        *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.dynamicres.actions.DynamicResourcesRootAction

import org.jenkins.plugins.lockableresources.LockableResources

def l = namespace(lib.LayoutTagLib.class)

l.layout(title:my.displayName) {
    l.main_panel {
        h1 _("Dynamic Resources")
        if(LockableResources.canRead()) {
            table(class:"pane", style:"width: 80%;") {
                tr {
                    td(class:"pane-header", _("Resource configuration"))
                }
                for(resource in my.dynamicResources) {
                    tr {
                        td(class:"pane", resource)
                    }
                }
            }
            p "Total amount of dynamic resources available: ${my.dynamicResourcesAmount}"
        }
    }
}
