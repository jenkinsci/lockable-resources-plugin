package org.jenkins.plugins.lockableresources.actions;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Run;
import hudson.security.Permission;
import hudson.security.AccessDeniedException3;
import jakarta.servlet.ServletException;
import java.util.ArrayList;
import java.io.IOException;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourceProperty;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.Messages;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Per-resource view/action endpoint under /lockable-resources/<resourceName>/.
 */
@Restricted(NoExternalUse.class)
public class LockableResourceDetailsAction {

    private final String resourceName;

    public LockableResourceDetailsAction(String resourceName) {
        this.resourceName = resourceName;
    }

    public String getResourceName() {
        return resourceName;
    }

    public String getDisplayName() {
        LockableResource resource = getResource();
        return resource != null ? resource.getName() : resourceName;
    }

    public Permission getRESERVE() {
        return LockableResourcesRootAction.RESERVE;
    }

    public Permission getUNLOCK() {
        return LockableResourcesRootAction.UNLOCK;
    }

    public Permission getSTEAL() {
        return LockableResourcesRootAction.STEAL;
    }

    public Permission getCONFIGURE() {
        return LockableResourcesRootAction.CONFIGURE;
    }

    @CheckForNull
    public LockableResource getResource() {
        Jenkins.get().checkPermission(LockableResourcesRootAction.VIEW);
        return LockableResourcesManager.get().fromName(resourceName);
    }

    public boolean isMissing() {
        return getResource() == null;
    }

    public String getState() {
        LockableResource resource = getResource();
        if (resource == null) {
            return "missing";
        }
        if (resource.getReservedBy() != null) {
            return "reserved";
        }
        if (resource.getRemoteLockedBy() != null) {
            return "remoteLocked";
        }
        if (resource.isLocked()) {
            return "locked";
        }
        if (resource.isQueued()) {
            return "queued";
        }
        return "free";
    }

    @CheckForNull
    public Run<?, ?> getBuild() {
        LockableResource resource = getResource();
        return resource != null ? resource.getBuild() : null;
    }

    @CheckForNull
    public String getUserName() {
        return LockableResource.getUserName();
    }

    private List<LockableResource> singletonResourceOr404(StaplerResponse2 rsp) throws IOException {
        LockableResource resource = LockableResourcesManager.get().fromName(resourceName);
        if (resource == null) {
            rsp.sendError(404, Messages.error_resourceDoesNotExist(resourceName));
            return null;
        }
        return List.of(resource);
    }

    @RequirePOST
    public void doUnlock(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(LockableResourcesRootAction.UNLOCK);
        List<LockableResource> resources = singletonResourceOr404(rsp);
        if (resources == null) {
            return;
        }
        LockableResourcesManager.get().unlockResources(resources);
        rsp.forwardToPreviousPage(req);
    }

    @RequirePOST
    public void doReserve(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(LockableResourcesRootAction.RESERVE);
        List<LockableResource> resources = singletonResourceOr404(rsp);
        if (resources == null) {
            return;
        }

        String userName = getUserName();
        if (userName == null) {
            rsp.sendError(401, Messages.error_notAuthenticated());
            return;
        }

        String reason = Util.fixEmptyAndTrim(req.getParameter("reason"));
        boolean ok = LockableResourcesManager.get().reserve(resources, userName, reason);
        if (!ok) {
            rsp.sendError(
                    423,
                    Messages.error_resourceAlreadyLocked(
                            LockableResourcesManager.getResourcesNames(resources)));
            return;
        }

        rsp.forwardToPreviousPage(req);
    }

    @RequirePOST
    public void doSteal(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(LockableResourcesRootAction.STEAL);
        List<LockableResource> resources = singletonResourceOr404(rsp);
        if (resources == null) {
            return;
        }

        String userName = getUserName();
        if (userName == null) {
            rsp.sendError(401, Messages.error_notAuthenticated());
            return;
        }

        String reason = Util.fixEmptyAndTrim(req.getParameter("reason"));
        LockableResourcesManager.get().steal(resources, userName, reason);
        rsp.forwardToPreviousPage(req);
    }

    @RequirePOST
    public void doUnreserve(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(LockableResourcesRootAction.RESERVE);
        List<LockableResource> resources = singletonResourceOr404(rsp);
        if (resources == null) {
            return;
        }

        String userName = getUserName();
        for (LockableResource resource : resources) {
            if ((userName == null || !userName.equals(resource.getReservedBy()))
                    && !Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                throw new AccessDeniedException3(Jenkins.getAuthentication2(), LockableResourcesRootAction.RESERVE);
            }
        }

        LockableResourcesManager.get().unreserve(resources);
        rsp.forwardToPreviousPage(req);
    }

    @RequirePOST
    public void doReset(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(LockableResourcesRootAction.UNLOCK);
        List<LockableResource> resources = singletonResourceOr404(rsp);
        if (resources == null) {
            return;
        }
        LockableResourcesManager.get().reset(resources);
        rsp.forwardToPreviousPage(req);
    }

    @RequirePOST
    public void doConfigSubmit(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Jenkins.get().checkPermission(LockableResourcesRootAction.CONFIGURE);

        LockableResourcesManager manager = LockableResourcesManager.get();
        synchronized (LockableResourcesManager.syncResources) {
            LockableResource resource = manager.fromName(resourceName);
            if (resource == null) {
                rsp.sendError(404, Messages.error_resourceDoesNotExist(resourceName));
                return;
            }

            resource.setDescription(Util.fixEmptyAndTrim(req.getParameter("description")));
            resource.setLabelsFromString(Util.fixEmptyAndTrim(req.getParameter("labels")));

            List<LockableResourceProperty> properties = new ArrayList<>();
            String[] names = req.getParameterValues("propertyName");
            String[] values = req.getParameterValues("propertyValue");
            if (names != null) {
                for (int i = 0; i < names.length; i++) {
                    String name = Util.fixEmptyAndTrim(names[i]);
                    if (name == null) {
                        continue;
                    }
                    String value = values != null && values.length > i ? Util.fixEmptyAndTrim(values[i]) : null;

                    LockableResourceProperty property = new LockableResourceProperty();
                    property.setName(name);
                    property.setValue(value);
                    properties.add(property);
                }
            }
            resource.setProperties(properties);

            manager.save();
        }

        rsp.sendRedirect2("../" + Util.rawEncode(resourceName) + "/");
    }
}
