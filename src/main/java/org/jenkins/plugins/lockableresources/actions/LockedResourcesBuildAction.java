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
import hudson.model.Run;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;

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
        /// FIXME move to localization files
        return "Locked Resources";
    }

    @Override
    public String getUrlName() {
        return "locked-resources";
    }

    public static void updateAction(Run<?, ?> build, List<String> resourceNames) {
        LockedResourcesBuildAction action = build.getAction(LockedResourcesBuildAction.class);

        if (action == null) {
            List<ResourcePOJO> resPojos = new ArrayList<>();
            action = new LockedResourcesBuildAction(resPojos);
            build.addAction(action);
        }

        for (String name : resourceNames) {
            LockableResource r = LockableResourcesManager.get().fromName(name);
            action.add(new ResourcePOJO(r));
        }
    }

    public void add(ResourcePOJO r) {
        for (ResourcePOJO pojo : this.lockedResources) {
            if (pojo.getName().equals(r.getName())) {
                pojo.inc();
                return;
            }
        }
        this.lockedResources.add(r);
    }

    public static LockedResourcesBuildAction fromResources(Collection<LockableResource> resources) {
        List<ResourcePOJO> resPojos = new ArrayList<>();
        for (LockableResource r : resources) resPojos.add(new ResourcePOJO(r));
        return new LockedResourcesBuildAction(resPojos);
    }

    public static class ResourcePOJO {

        private String name;
        private String description;
        private int count = 1;

        public ResourcePOJO(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public ResourcePOJO(LockableResource r) {
            this.name = r.getName();
            this.description = r.getDescription();
        }

        public String getName() {
            return this.name;
        }

        public String getDescription() {
            return this.description;
        }

        public int getCounter() {
            return this.count;
        }

        public void inc() {
            this.count++;
        }
    }
}
