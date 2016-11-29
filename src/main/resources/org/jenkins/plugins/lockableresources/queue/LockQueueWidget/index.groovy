/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
* Copyright (c) 2016, Eb                                              *
*                                                                     *
* This file is part of the Jenkins Lockable Resources Plugin and is   *
* published under the MIT license.                                    *
*                                                                     *
* See the "LICENSE.txt" file for more information.                    *
* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue.LockQueueWidget

import org.jenkins.plugins.lockableresources.LockableResources

def f = namespace(lib.FormTagLib.class)
def l = namespace(lib.LayoutTagLib.class)
def st = namespace("jelly:stapler")

if(LockableResources.canRead()) {
    style "#lockQueue {margin-top: 20px;}"
    l.pane(width:"2", title:_("Lock queue"), id:"lockQueue") {
        def queue = my.getQueue()
        for(int i = 0; i < queue.size(); i++) {
            def item = queue[i]
            tr {
                td (i+1)
                td item.id
                td item.resourcesString
            }
        }
    }
}
