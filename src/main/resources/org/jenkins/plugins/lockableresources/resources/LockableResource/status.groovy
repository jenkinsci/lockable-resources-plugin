/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.resources.LockableResource

import hudson.security.SecurityRealm
import hudson.markup.MarkupFormatterDescriptor
import hudson.security.AuthorizationStrategy
import jenkins.AgentProtocol
import jenkins.model.GlobalConfiguration
import hudson.Functions
import hudson.model.Descriptor
import org.jenkins.plugins.lockableresources.queue.policy.QueuePolicy
import org.jenkins.plugins.lockableresources.resources.selector.ResourcesSelector

import org.jenkins.plugins.lockableresources.Utils
import org.jenkins.plugins.lockableresources.LockableResources
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;

def userId = Utils.getUserId()
def canReserve = app.hasPermission(LockableResources.RESERVE)
def canOffline = app.hasPermission(LockableResources.OFFLINE)
def canUnlock = app.hasPermission(LockableResources.UNLOCK)
def manager = LockableResourcesManager.get()
def safeName = my.name.replaceAll("\\W", "_") + "_" + System.currentTimeMillis()

// Variable "compact" (true/false) must be set before calling this script
// true: no buttons + compact string (for widget)
// false: full status infos (for root action)
tr {
    td(class:"pane") {
        strong my.name
        if(!compact) {
            div my.description
        }
    }
    td(class:"pane") {
        // Manage first the lock status (free/queued/locked)
        if(my.locked) {
            div(style:"color: blue;") {
                if(!compact) {
                    strong _("LOCKED")
                    text _(" by ")
                }
                a(href:rootURL + "/" + my.build.url, style:"color: blue;", my.build.fullDisplayName)
            }
        } else if(my.queued) {
            div(style:"color: red;") {
                strong _("QUEUED")
                text _(" by ")
                text my.queueItemProject my.queueItemId
            }
        } else if(my.hasExclusiveUse(userId)) {
            div(style:"color: green;") {
                strong _("EXCLUSIVE")
                if(!compact) {
                    text " " + my.reservedUntilString
                }
            }
        } else if(!my.isReserved(userId)) {
            div(style:"color: green;") {
                strong _("AVAILABLE")
            }
        } else if(my.reservedFor != null) {
            div(style:"color: #800080;") {
                strong _("RESERVED")
                if(!compact) {
                    text " " + my.reservedUntilString
                }
            }
        } else if(my.reservedBy != null) {
            div(style:"color: grey;") {
                strong _("OFFLINE")
            }
        } else {
            // Reserved but by no one... probably a bug
            div {
                text "---"
            }
        }
        // Then, manage online/offline and reservation
        if(!compact) {
            if(my.reservedFor != null) {
                div(style:"color: #800080;") {
                    strong _("RESERVED")
                    text _(" by ")
                    text my.reservedByName
                    text _(" for ")
                    strong my.reservedForName
                }
            } else if(my.reservedBy != null) {
                div(style:"color: grey;") {
                    strong _("RESERVED")
                    text _(" by ")
                    strong my.reservedByName
                }
            }
        }
    }
    if(!compact) {
        td(class:"pane") {
            text my.labels
        }
        td(class:"pane") {
            script {
                text raw("""function unlock_resource_${safeName}() {
if(!confirm('Are you sure ? A conflict with another job may occures.')) {return}
    window.location.assign('unlock?resource=${my.name}');
}
function reserve_resource_${safeName}() {
    window.location.assign('reserve?resource=${my.name}');
}
function unreserve_resource_${safeName}() {
    if(!confirm('Are you sure ? Anyone will be able to use this resource (even queued jobs).')) {return}
    window.location.assign('unreserve?resource=${my.name}');
}
function reset_resource_${safeName}() {
    if(!confirm('Are you sure ? A conflict with another job may occures.')) {return}
    window.location.assign('reset?resource=${my.name}');
}
function reserveFor_resource_${safeName}() {
    var forUser = prompt('Id or full name of the user:', '${userId}');
    if(forUser == null) {return}
    if(${manager.defaultReservationHours > 0}) {
        var hours;
        var txt = 'Duration of the reservation';
        if(${canOffline}) {
            txt += ' (hours, 0 = unlimited):';
        } else if(${manager.maxReservationHours > 0}) {
            txt += ' (max = ${manager.maxReservationHours} hours):';
        } else {
            txt += ' (hours):';
        }
        hours = prompt(txt, '${manager.defaultReservationHours}');
        if(hours == null) {return}
        console.log("1/ forUser = " + forUser)
        window.location.assign('reserveFor?resource=${my.name}""" + /&/ + """forUser=' + forUser + '&hours=' + hours);
    } else {\n\
        console.log("2/ forUser = " + forUser)
        window.location.assign('reserveFor?resource=${my.name}&forUser=' + forUser);
    }
}
function unreserveFor_resource_${safeName}() {
    if(!confirm('Are you sure ? You will lost reservation for this resource.')) {return}
    window.location.assign('unreserveFor?resource=${my.name}');
}""")
            }
            if(my.locked) {
                if(canUnlock) {
                    button(onClick:"unlock_resource_${safeName}()", _("Unlock"))
                }
            } else if(my.queued) {
                if(canUnlock) {
                    button(onClick:"reset_resource_${safeName}()", _("Reset"))
                }
            }
            if(my.reservedFor != null) {
                def reservedByMe = (userId == my.reservedBy)
                def reservedForMe = (userId == my.reservedFor)
                if(reservedByMe) {
                    button(onClick:"reserveFor_resource_${safeName}()", _("Change reservation"))
                }
                if(canOffline) {
                    button(onClick:"reserve_resource_${safeName}()", _("Set offline"))
                }
                if(canUnlock || (reservedByMe && canReserve) || reservedForMe) {
                    button(onClick:"unreserveFor_resource_${safeName}()", _("UnReserve"))
                }
            } else if(my.reservedBy != null) {
                if(canOffline && canReserve) {
                    button(onClick:"reserveFor_resource_${safeName}()", _("Online + reservation"))
                }
                if(canOffline || canUnlock) {
                    button(onClick:"unreserve_resource_${safeName}()", _("Set online"))
                }
            } else {
                if(canReserve) {
                    button(onClick:"reserveFor_resource_${safeName}()", _("Reserve"))
                }
                if(canOffline) {
                    button(onClick:"reserve_resource_${safeName}()", _("Set offline"))
                }
            }
        }
    }
}
