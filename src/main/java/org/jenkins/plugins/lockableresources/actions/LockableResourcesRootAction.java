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
import javax.annotation.CheckForNull;
import javax.servlet.ServletException;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.BackwardCompatibility;
import org.jenkins.plugins.lockableresources.Messages;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Exported;

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
    public static final Permission OFFLINE = new Permission(PERMISSIONS_GROUP,
            Messages.offlinePermission(),
            Messages._offlinePermission_Description(), Jenkins.ADMINISTER,
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

    @Override
    public String getDisplayName() {
        return "Lockable Resources";
    }

    @Override
    public String getUrlName() {
        return "lockable-resources";
    }

    @Exported
    public Set<LockableResource> getResources() {
        return LockableResourcesManager.get().getResources();
    }

    @Exported
    public int getFreeResourceAmount(String labels) {
        return LockableResourcesManager.get().getFreeAmount(labels, null, null);
    }

    @Exported
    public Set<String> getAllLabels() {
        return LockableResourcesManager.get().getAllLabels(false);
    }

    @Exported
    public int getNumberOfAllLabels() {
        return LockableResourcesManager.get().getAllLabels(false).size();
    }

    @Exported
    public void doUnlock(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
        if(!Jenkins.getInstance().hasPermission(UNLOCK)) {
            throw new AccessDeniedException2(Jenkins.getAuthentication(), UNLOCK);
        }

        String name = req.getParameter("resource");
        LockableResourcesManager manager = LockableResourcesManager.get();
        LockableResource r = manager.getResourceFromName(name);
        if(r == null) {
            rsp.sendError(404, "Resource not found " + name);
            return;
        }

        List<LockableResource> resources = new ArrayList<>();
        resources.add(r);
        manager.unlock(resources);

        rsp.forwardToPreviousPage(req);
    }

    @Exported
    public void doReserve(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
        if(!Jenkins.getInstance().hasPermission(OFFLINE)) {
            throw new AccessDeniedException2(Jenkins.getAuthentication(), OFFLINE);
        }

        String name = req.getParameter("resource");
        LockableResourcesManager manager = LockableResourcesManager.get();
        LockableResource r = manager.getResourceFromName(name);
        if(r == null) {
            rsp.sendError(404, "Resource not found " + name);
            return;
        }

        List<LockableResource> resources = new ArrayList<>();
        resources.add(r);
        manager.reserve(resources);

        rsp.forwardToPreviousPage(req);
    }

    @Exported
    public void doReserveFor(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
        if(!Jenkins.getInstance().hasPermission(RESERVE)) {
            throw new AccessDeniedException2(Jenkins.getAuthentication(), RESERVE);
        }

        String name = req.getParameter("resource");
        LockableResourcesManager manager = LockableResourcesManager.get();
        LockableResource r = manager.getResourceFromName(name);
        if(r == null) {
            rsp.sendError(404, "Resource not found " + name);
            return;
        }
        String forUser = req.getParameter("forUser");
        Double hours;
        try {
            hours = Double.parseDouble(req.getParameter("hours"));
        } catch(NumberFormatException ex) {
            hours = 12.0; //default value = 12hours
        }

        List<LockableResource> resources = new ArrayList<>();
        resources.add(r);
        manager.reserve(resources, forUser, hours);

        rsp.forwardToPreviousPage(req);
    }

    @Exported
    public void doUnreserve(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
        if(!(Jenkins.getInstance().hasPermission(UNLOCK) || Jenkins.getInstance().hasPermission(OFFLINE))) {
            throw new AccessDeniedException2(Jenkins.getAuthentication(), OFFLINE);
        }
        String name = req.getParameter("resource");
        LockableResourcesManager manager = LockableResourcesManager.get();
        LockableResource r = manager.getResourceFromName(name);
        if(r == null) {
            rsp.sendError(404, "Resource not found " + name);
            return;
        }

        List<LockableResource> resources = new ArrayList<>();
        resources.add(r);
        manager.unreserve(resources);

        rsp.forwardToPreviousPage(req);
    }

    @Exported
    public void doUnreserveFor(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
        String name = req.getParameter("resource");
        LockableResourcesManager manager = LockableResourcesManager.get();
        LockableResource r = manager.getResourceFromName(name);
        if(r == null) {
            rsp.sendError(404, "Resource not found " + name);
            return;
        }
        /*
         * Can unreserve if UNLOCK/OFFLINE or RESERVE+reservedBy=me or reservedFor=me
         */
        String userId = Utils.getUserId();
        if(!(Jenkins.getInstance().hasPermission(UNLOCK))) {
            if((userId == null) || !(
                    (Jenkins.getInstance().hasPermission(RESERVE) && userId.equals(r.getReservedBy()))
                    || userId.equals(r.getReservedFor()))) {
                throw new AccessDeniedException2(Jenkins.getAuthentication(), UNLOCK);
            }
        }

        List<LockableResource> resources = new ArrayList<>();
        resources.add(r);
        manager.unreserve(resources);

        rsp.forwardToPreviousPage(req);
    }

    @Exported
    public void doReset(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
        if(!Jenkins.getInstance().hasPermission(UNLOCK)) {
            throw new AccessDeniedException2(Jenkins.getAuthentication(), UNLOCK);
        }

        String name = req.getParameter("resource");
        LockableResourcesManager manager = LockableResourcesManager.get();
        LockableResource r = manager.getResourceFromName(name);
        if(r == null) {
            rsp.sendError(404, "Resource not found " + name);
            return;
        }

        List<LockableResource> resources = new ArrayList<>();
        resources.add(r);
        manager.reset(resources);
        
        rsp.forwardToPreviousPage(req);
    }
    
    @Exported
    @CheckForNull
    public String getUserId() {
        return Utils.getUserId();
    }
    
    @Exported
    @CheckForNull
    public Double getDefaultReservationHours() {
        LockableResourcesManager manager = LockableResourcesManager.get();
        return manager.getDefaultReservationHours();
    }
    
    @Exported
    @CheckForNull
    public Double getMaxReservationHours() {
        LockableResourcesManager manager = LockableResourcesManager.get();
        return manager.getMaxReservationHours();
    }
}
