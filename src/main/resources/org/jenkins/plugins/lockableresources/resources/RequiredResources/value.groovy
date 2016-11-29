/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
* Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
*                                                                     *
* This file is part of the Jenkins Lockable Resources Plugin and is   *
* published under the MIT license.                                    *
*                                                                     *
* See the "LICENSE.txt" file for more information.                    *
* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.resources.RequiredResources

def f = namespace(lib.FormTagLib.class)

instance = my
f.entry(title:_("Resources names")) {
    f.readOnlyTextbox(field:"resources")
}
f.entry(title:_("Capabilities")) {
    f.readOnlyTextbox(field:"labels")
}
f.entry(title:_("Number of resources with selected capabilities")) {
    f.readOnlyTextbox(field:"quantity")
}
