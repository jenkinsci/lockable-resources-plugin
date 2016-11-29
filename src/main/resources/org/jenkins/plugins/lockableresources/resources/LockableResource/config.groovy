/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.resources.LockableResource

def f = namespace(lib.FormTagLib.class)

f.entry(title:_("Name")) {
    f.textbox(field:"name")
}
f.entry(title:_("Description")) {
    f.textbox(field:"description")
}
f.entry(title:_("Capabilities")) {
    f.textbox(field:"labels")
}
