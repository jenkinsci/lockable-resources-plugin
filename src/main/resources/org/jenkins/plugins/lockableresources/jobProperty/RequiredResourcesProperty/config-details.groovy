/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.jobProperty.RequiredResourcesProperty

def f = namespace(lib.FormTagLib.class)
def st = namespace("jelly:stapler")

f.entry(title:_("Variable name")) {
    f.textbox(field:"variableName")
}
f.entry(title:_("Lock retry timeout (seconds)")) {
    f.textbox(field:"timeout")
}
f.entry(title:_("Lockable Resources")) {
    f.repeatable(field:"requiredResourcesList", header:_("Required resources"), minimum:"1", add:_("Add required resources")) {
        table(width:"100%") {
            st.include(page:"config.groovy", class:"org.jenkins.plugins.lockableresources.resources.RequiredResources")
            f.repeatableDeleteButton()
        }
    }
}
