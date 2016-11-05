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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jenkins.plugins.lockableresources.resources.LockableResource;

public class LockedResourcesBuildAction implements Action {
    private final List<ResourcePOJO> lockedResources;

    public LockedResourcesBuildAction(List<ResourcePOJO> lockedResources) {
        this.lockedResources = lockedResources;
    }

    public List<ResourcePOJO> getLockedResources() {
        return lockedResources;
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

    public static LockedResourcesBuildAction fromResources(
            Collection<LockableResource> resources) {
        List<ResourcePOJO> resPojos = new ArrayList<>();
        for(LockableResource r : resources) {
            resPojos.add(new ResourcePOJO(r));
        }
        return new LockedResourcesBuildAction(resPojos);
    }

    public static class ResourcePOJO {
        private String name;
        private String description;

        public ResourcePOJO(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public ResourcePOJO(LockableResource resource) {
            this.name = resource.getName();
            this.description = resource.getDescription();
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }
    }
}
