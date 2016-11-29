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
package org.jenkins.plugins.lockableresources.dynamicres.actions.DynamicResourcesBuildAction

import java.lang.Thread;

def l = namespace(lib.LayoutTagLib.class)
def st = namespace("jelly:stapler")

def currentThread = Thread.currentThread()
def buildClass = currentThread.contextClassLoader.loadClass("hudson.model.Run")
def build = request.findAncestorObject(buildClass)

l.layout(title:_(my.displayName)) {
    st.include(page:"sidepanel.jelly", it:build)
    l.main_panel {
        h1 _("Dynamic Resources")
        br()
        p "This build creates the following dynamic resources:"
        table(class:"pane", style:"width: 50%;") {
            tr {
                td(class:"pane-header", "Resource configuration")
            }
            for(dynamicRes in my.createdByJob) {
                tr {
                    td(class:"pane") {
                        b {
                            i dynamicRes
                        }
                    }
                }
            }
        }
        p "Total amount of dynamic resources created: ${my.createdAmount}"
        br()
        p "This build consumes the following dynamic resources:"
        table(class:"pane", style:"width: 50%;") {
            tr {
                td(class:"pane-header", "Resource configuration")
            }
            for(dynamicRes in my.consumedByJob) {
                tr {
                    td(class:"pane") {
                        b {
                            i dynamicRes
                        }
                    }
                }
            }
        }
        p "Total amount of dynamic resources created: ${my.consumedAmount}"
    }
}
