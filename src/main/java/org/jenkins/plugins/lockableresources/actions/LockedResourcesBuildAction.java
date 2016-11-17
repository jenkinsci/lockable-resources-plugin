/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.actions;

import hudson.model.Action;
import java.util.Collections;
import java.util.Set;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.export.Exported;

public class LockedResourcesBuildAction implements Action {
    private final Set<LockableResource> lockedResources;

    @DataBoundConstructor
    public LockedResourcesBuildAction(Set<LockableResource> lockedResources) {
        this.lockedResources = lockedResources;
    }

    @Exported
    public Set<LockableResource> getLockedResources() {
        return Collections.unmodifiableSet(lockedResources);
    }

    @Override
    public String getIconFileName() {
        return LockableResourcesRootAction.ICON;
    }

    @Override
    public String getDisplayName() {
        return "Locked Resources";
    }

    @Override
    public String getUrlName() {
        return "locked-resources";
    }
}
