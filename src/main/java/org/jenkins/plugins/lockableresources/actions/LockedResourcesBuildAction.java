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
import java.util.Date;
import java.util.List;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.Messages;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

// -----------------------------------------------------------------------------
/** BuildAction for lockable resources.
 * Shows usage of resources in the build page.
 * url: jobUrl/buildNr/locked-resources/
 */
@Restricted(NoExternalUse.class)
public class LockedResourcesBuildAction implements Action {

    // -------------------------------------------------------------------------
    private final List<ResourcePOJO> lockedResources;

    /** Object to synchronized operations over LRM */
    private static final transient Object syncResources = new Object();

    // -------------------------------------------------------------------------
    public LockedResourcesBuildAction(List<ResourcePOJO> lockedResources) {
        synchronized (this.syncResources) {
          this.lockedResources = lockedResources;
        }
    }

    // -------------------------------------------------------------------------
    public List<ResourcePOJO> getLockedResources() {
        synchronized (this.syncResources) {
          return lockedResources;
        }
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
     * Used in pipelines - lock() step
     */
    @Restricted(NoExternalUse.class)
    public static void updateAction(Run<?, ?> build, List<String> resourceNames, String action, String step) {
        LockedResourcesBuildAction buildAction = build.getAction(LockedResourcesBuildAction.class);

        if (buildAction == null) {
            List<ResourcePOJO> resPojos = new ArrayList<>();
            buildAction = new LockedResourcesBuildAction(resPojos);
            build.addAction(buildAction);
        }

        for (String name : resourceNames) {
            buildAction.add(new ResourcePOJO(name, step, action));
        }
    }

    // -------------------------------------------------------------------------
    /** Add the resource to build action.*/
    @Restricted(NoExternalUse.class)
    // since the list *this.lockedResources* might be updated from multiple (parallel)
    // stages, this operation need to be synchronized
    private synchronized void add(ResourcePOJO r) {
        synchronized (this.syncResources) {
          this.lockedResources.add(r);
        }
    }

    // -------------------------------------------------------------------------
    /** Create action from resources.
     * Used in free-style projects.
     */
    @Restricted(NoExternalUse.class)
    public static LockedResourcesBuildAction fromResources(Collection<LockableResource> resources) {
        List<ResourcePOJO> resPojos = new ArrayList<>();
        for (LockableResource r : resources) {
            if (r != null) {
                resPojos.add(new ResourcePOJO(r.getName(), "", ""));
            }
        }
        return new LockedResourcesBuildAction(resPojos);
    }

    // -------------------------------------------------------------------------
    public static class ResourcePOJO {

        // ---------------------------------------------------------------------
        private String name;
        private String step;
        private String action;
        private long timeStamp;

        // ---------------------------------------------------------------------
        public ResourcePOJO(String name, String step, String action) {
            this.name = name;
            this.step = step;
            this.action = action;
            this.timeStamp = new Date().getTime();
        }

        // ---------------------------------------------------------------------
        public String getName() {
            return this.name;
        }

        // ---------------------------------------------------------------------
        public String getStep() {
            return this.step;
        }

        // ---------------------------------------------------------------------
        public String getAction() {
            return this.action;
        }

        // ---------------------------------------------------------------------
        public Date getTimeStamp() {
            return new Date(this.timeStamp);
        }
    }
}
