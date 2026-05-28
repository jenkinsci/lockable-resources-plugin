package org.jenkins.plugins.lockableresources.actions;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.ManagementLink;
import hudson.model.RootAction;
import hudson.security.Permission;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.Messages;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * ManagementLink that renders the Lockable Resources page inside the Manage Jenkins
 * settings-subpage layout (with the Manage Jenkins sidebar). This keeps the plugin
 * discoverable and integrated within the management UI for admins.
 *
 * <p>Non-admin users with only the VIEW permission access the page via the
 * {@link LockableResourcesRootAction} at {@code /lockable-resources/} instead.
 */
@Extension
@Restricted(NoExternalUse.class)
public class LockableResourcesManagementLink extends ManagementLink {

    @Override
    public String getIconFileName() {
        return Jenkins.get().hasPermission(LockableResourcesRootAction.VIEW)
                ? LockableResourcesRootAction.ICON
                : null;
    }

    @Override
    public String getDisplayName() {
        return Messages.LockableResourcesRootAction_PermissionGroup();
    }

    @Override
    public String getUrlName() {
        return "lockable-resources";
    }

    @Override
    public String getDescription() {
        return Messages.LockableResourcesRootAction_ManagementLink_Description();
    }

    @NonNull
    @Override
    public Category getCategory() {
        return Category.CONFIGURATION;
    }

    @NonNull
    @Override
    public Permission getRequiredPermission() {
        return LockableResourcesRootAction.VIEW;
    }

    /**
     * Returns the RootAction instance so the Jelly view can delegate content rendering.
     * Used by {@code index.jelly}.
     */
    @Restricted(NoExternalUse.class)
    public LockableResourcesRootAction getAction() {
        return Jenkins.get().getExtensionList(RootAction.class).get(LockableResourcesRootAction.class);
    }
}
