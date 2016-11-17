/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2015, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import hudson.Plugin;
import hudson.model.Api;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import java.util.Collections;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

@ExportedBean
public class LockableResources extends Plugin {
    public static final PermissionGroup PERMISSIONS_GROUP = new PermissionGroup(
            LockableResourcesManager.class, Messages._permissionGroup());
    public static final Permission UNLOCK = new Permission(PERMISSIONS_GROUP,
            Messages.unlockPermission(),
            Messages._unlockPermission_Description(), Jenkins.ADMINISTER,
            PermissionScope.JENKINS);
    public static final Permission OFFLINE = new Permission(PERMISSIONS_GROUP,
            Messages.offlinePermission(),
            Messages._offlinePermission_Description(), Jenkins.ADMINISTER,
            PermissionScope.JENKINS);
    public static final Permission RESERVE = new Permission(PERMISSIONS_GROUP,
            Messages.reservePermission(),
            Messages._reservePermission_Description(), OFFLINE,
            PermissionScope.JENKINS);
    public static final Permission READ = new Permission(PERMISSIONS_GROUP,
            Messages.readPermission(),
            Messages._readPermission_Description(), RESERVE,
            PermissionScope.JENKINS);
    
    public Api getApi() {
        return new Api(this);
    }

    @Exported
    public Set<LockableResource> getResources() {
        return Collections.unmodifiableSet(LockableResourcesManager.get().getResources());
    }

    @Exported
    public static boolean canRead() {
        Jenkins jenkins = Jenkins.getInstance();
        return jenkins.hasPermission(READ) || jenkins.hasPermission(RESERVE) || jenkins.hasPermission(OFFLINE) || jenkins.hasPermission(UNLOCK);
    }
}
