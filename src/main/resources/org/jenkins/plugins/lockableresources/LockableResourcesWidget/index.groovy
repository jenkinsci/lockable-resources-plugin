/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2016, Eb                                              *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.LockableResourcesWidget

import org.jenkins.plugins.lockableresources.LockableResources

def l = namespace(lib.LayoutTagLib.class)
def st = namespace("jelly:stapler")

if(LockableResources.canRead()) {
    style "#lockableResources {margin-top: 20px;}"
    l.pane(width:"2", title:_("Lockable resources"), id:"lockableResources") {
        compact = true
        for(resource in my.resources) {
            st.include(it:resource, page:"status.groovy")
        }
    }
}
