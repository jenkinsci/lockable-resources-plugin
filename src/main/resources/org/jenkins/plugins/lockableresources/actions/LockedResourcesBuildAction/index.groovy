/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction

import java.lang.Thread;

def l = namespace(lib.LayoutTagLib.class)
def st = namespace("jelly:stapler")

def currentThread = Thread.currentThread()
def buildClass = currentThread.contextClassLoader.loadClass("hudson.model.Run")
def build = request.findAncestorObject(buildClass)

l.layout(title:_(my.displayName)) {
    st.include(page:"sidepanel.jelly", it:build)
    l.main_panel {
        h1 _("Locked Resources")
        p "This build has locked the following resources:"
        ul {
            for(data in my.buildData) {
                resource = data.resource
                date = data.getDateString()
                li {
                    strong resource.name
                    text " - "
                    em resource.description
                    text " (locked on " + date + ")"
                }
            }
        }
    }
}
