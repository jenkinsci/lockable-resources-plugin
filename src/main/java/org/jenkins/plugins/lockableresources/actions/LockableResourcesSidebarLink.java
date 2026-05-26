package org.jenkins.plugins.lockableresources.actions;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.RootAction;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.Messages;

/**
 * Adds a top-level sidebar link so users with VIEW permission can discover
 * the Lockable Resources page without navigating through Manage Jenkins.
 */
@Extension
public class LockableResourcesSidebarLink implements RootAction {

    @CheckForNull
    @Override
    public String getIconFileName() {
        return Jenkins.get().hasPermission(LockableResourcesRootAction.VIEW) ? LockableResourcesRootAction.ICON : null;
    }

    @CheckForNull
    @Override
    public String getDisplayName() {
        return Jenkins.get().hasPermission(LockableResourcesRootAction.VIEW)
                ? Messages.LockableResourcesRootAction_PermissionGroup()
                : null;
    }

    @Override
    public String getUrlName() {
        return "/lockable-resources/";
    }
}
