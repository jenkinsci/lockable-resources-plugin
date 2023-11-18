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
import org.jenkins.plugins.lockableresources.Messages;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

// -----------------------------------------------------------------------------
/** BuildAction for lockable resources.
 * Shows usage of resources in the build page.
 * url: <jobUrl>/<buildNr>/locked-resources/
 */
@Restricted(NoExternalUse.class)
public class LockedResourcesBuildAction implements Action {

    // -------------------------------------------------------------------------
    private final List<ResourcePOJO> lockedResources;

    // -------------------------------------------------------------------------
    public LockedResourcesBuildAction(List<ResourcePOJO> lockedResources) {
        this.lockedResources = lockedResources;
    }

    // -------------------------------------------------------------------------
    public List<ResourcePOJO> getLockedResources() {
        return lockedResources;
    }

    // -------------------------------------------------------------------------
    @Override
    public String getIconFileName() {
        return LockableResourcesRootAction.ICON;
    }

    // -------------------------------------------------------------------------
    @Override
    public String getDisplayName() {
        return Messages.LockedResourcesBuildAction_displayName();
    }

    // -------------------------------------------------------------------------
    @Override
    public String getUrlName() {
        return "locked-resources";
    }

    // -------------------------------------------------------------------------
    /** Adds *resourceNames* to *build*.
     * When the action does not exists, will be created as well.
     * When the resource has been used by this build just now, the counter will
     * increased to eliminate multiple entries.
     * Used in pipelines - lock() step
     */
    @Restricted(NoExternalUse.class)
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

    // -------------------------------------------------------------------------
    /** Add the resource to build action.*/
    @Restricted(NoExternalUse.class)
    private void add(ResourcePOJO r) {
        for (ResourcePOJO pojo : this.lockedResources) {
            if (pojo.getName().equals(r.getName())) {
                pojo.inc();
                return;
            }
        }
        this.lockedResources.add(r);
    }

    // -------------------------------------------------------------------------
    /** Create action from resources.
     * Used in free-style projects.
     */
    @Restricted(NoExternalUse.class)
    public static LockedResourcesBuildAction fromResources(Collection<LockableResource> resources) {
        List<ResourcePOJO> resPojos = new ArrayList<>();
        for (LockableResource r : resources) resPojos.add(new ResourcePOJO(r));
        return new LockedResourcesBuildAction(resPojos);
    }

    // -------------------------------------------------------------------------
    public static class ResourcePOJO {

        // ---------------------------------------------------------------------
        private String name;
        private String description;
        private int count = 1;

        // ---------------------------------------------------------------------
        public ResourcePOJO(String name, String description) {
            this.name = name;
            this.description = description;
        }

        // ---------------------------------------------------------------------
        public ResourcePOJO(LockableResource r) {
            this.name = r.getName();
            this.description = r.getDescription();
        }

        // ---------------------------------------------------------------------
        public String getName() {
            return this.name;
        }

        // ---------------------------------------------------------------------
        public String getDescription() {
            return this.description;
        }

        // ---------------------------------------------------------------------
        /** Return the counter, how many was / is the resource used in the build.
         * Example: you can use the lock() function in parallel stages for the
         * same resource.
         */
        public int getCounter() {
            return this.count;
        }

        // ---------------------------------------------------------------------
        /** Increment counter */
        public void inc() {
            this.count++;
        }
    }
}
