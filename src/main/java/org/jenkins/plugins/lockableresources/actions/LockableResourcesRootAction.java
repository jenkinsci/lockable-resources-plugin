/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.actions;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.RootAction;
import hudson.model.User;
import hudson.security.AccessDeniedException2;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.servlet.ServletException;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.BackwardCompatibility;
import org.jenkins.plugins.lockableresources.Messages;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

@Extension
public class LockableResourcesRootAction implements RootAction {
    public static final PermissionGroup PERMISSIONS_GROUP = new PermissionGroup(
            LockableResourcesManager.class, Messages._permissionGroup());
    public static final Permission UNLOCK = new Permission(PERMISSIONS_GROUP,
            Messages.unlockPermission(),
            Messages._unlockPermission_Description(), Jenkins.ADMINISTER,
            PermissionScope.JENKINS);
    public static final Permission RESERVE = new Permission(PERMISSIONS_GROUP,
            Messages.reservePermission(),
            Messages._reservePermission_Description(), Jenkins.ADMINISTER,
            PermissionScope.JENKINS);
    static final String ICON = "/plugin/lockable-resources/img/device-24x24.png";

    /**
     * Backward compatibility
     */
    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void initBackwardCompatibility() {
        BackwardCompatibility.init();
    }

    @Override
    public String getIconFileName() {
        if(User.current() != null) {
            // only show if logged in
            return ICON;
        } else {
            return null;
        }
    }

    public String getUserName() {
        User user = User.current();

        if(user != null) {
            return user.getFullName();
        } else {
            return null;
        }
    }

    @Override
    public String getDisplayName() {
        return "Lockable Resources";
    }

    @Override
    public String getUrlName() {
        return "lockable-resources";
    }

    public Set<LockableResource> getResources() {
        return LockableResourcesManager.get().getResources();
    }

    public int getFreeResourceAmount(String labels) {
        return LockableResourcesManager.get().getFreeAmount(labels, null);
    }

    public Set<String> getAllLabels() {
        return LockableResourcesManager.get().getAllLabels(false);
    }

    public int getNumberOfAllLabels() {
        return LockableResourcesManager.get().getAllLabels(false).size();
    }

    public void doUnlock(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
        if(!Jenkins.getInstance().hasPermission(UNLOCK)) {
            throw new AccessDeniedException2(Jenkins.getAuthentication(), UNLOCK);
        }

        String name = req.getParameter("resource");
        LockableResource r = LockableResourcesManager.get().getResourceFromName(name);
        if(r == null) {
            rsp.sendError(404, "Resource not found " + name);
            return;
        }

        List<LockableResource> resources = new ArrayList<>();
        resources.add(r);
        LockableResourcesManager.get().unlock(resources);

        rsp.forwardToPreviousPage(req);
    }

    public void doReserve(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
        if(!Jenkins.getInstance().hasPermission(RESERVE)) {
            throw new AccessDeniedException2(Jenkins.getAuthentication(), RESERVE);
        }

        String name = req.getParameter("resource");
        LockableResource r = LockableResourcesManager.get().getResourceFromName(name);
        if(r == null) {
            rsp.sendError(404, "Resource not found " + name);
            return;
        }

        List<LockableResource> resources = new ArrayList<>();
        resources.add(r);
        String userName = getUserName();
        if(userName != null) {
            LockableResourcesManager.get().reserve(resources, userName);
        }

        rsp.forwardToPreviousPage(req);
    }

    public void doUnreserve(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
        if(!(Jenkins.getInstance().hasPermission(UNLOCK) || Jenkins.getInstance().hasPermission(RESERVE))) {
            throw new AccessDeniedException2(Jenkins.getAuthentication(), RESERVE);
        }

        String name = req.getParameter("resource");
        LockableResource r = LockableResourcesManager.get().getResourceFromName(name);
        if(r == null) {
            rsp.sendError(404, "Resource not found " + name);
            return;
        }

        String userName = getUserName();
        if(!Jenkins.getInstance().hasPermission(UNLOCK)) {
            if((userName == null) || !userName.equals(r.getReservedBy())) {
                throw new AccessDeniedException2(Jenkins.getAuthentication(), RESERVE);
            }
        }

        List<LockableResource> resources = new ArrayList<>();
        resources.add(r);
        LockableResourcesManager.get().unreserve(resources);

        rsp.forwardToPreviousPage(req);
    }

    public void doReset(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
        if(!Jenkins.getInstance().hasPermission(UNLOCK)) {
            throw new AccessDeniedException2(Jenkins.getAuthentication(), UNLOCK);
        }

        String name = req.getParameter("resource");
        LockableResource r = LockableResourcesManager.get().getResourceFromName(name);
        if(r == null) {
            rsp.sendError(404, "Resource not found " + name);
            return;
        }

        List<LockableResource> resources = new ArrayList<>();
        resources.add(r);
        LockableResourcesManager.get().reset(resources);

        rsp.forwardToPreviousPage(req);
    }
}
