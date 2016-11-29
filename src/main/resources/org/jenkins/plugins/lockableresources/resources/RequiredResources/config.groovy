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

f.entry(title:_("Resources names")) {
    f.textbox(field:"resources", autoCompleteDelimChar:",")
}
f.entry(title:_("Capabilities")) {
    f.textbox(field:"labels", autoCompleteDelimChar:",")
}
f.entry(title:_("Number of resources with selected capabilities")) {
    f.textbox(field:"quantity")
}
