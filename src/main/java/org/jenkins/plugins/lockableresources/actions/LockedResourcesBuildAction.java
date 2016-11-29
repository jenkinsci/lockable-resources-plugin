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
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.export.Exported;

public class LockedResourcesBuildAction implements Action {
    public static class BuildActionData {
        @Exported
        public final LockableResource resource;
        @Exported
        public final Calendar dateTime;

        public BuildActionData(LockableResource resource) {
            this(resource, Calendar.getInstance());
        }
        
        public BuildActionData(LockableResource resource, Calendar dateTime) {
            this.resource = resource;
            this.dateTime = dateTime;
        }
        
        @Exported
        public String getDateString() {
            SimpleDateFormat formater = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SS");
            Date d = dateTime.getTime();
            return formater.format(d);
        }
    }
    @Deprecated
    @Exported
    protected final Set<LockableResource> lockedResources = new LinkedHashSet<>();
    @Exported
    protected Set<BuildActionData> buildData = new LinkedHashSet<>();

    @DataBoundConstructor
    public LockedResourcesBuildAction() {
    }
    
    public LockedResourcesBuildAction(Set<LockableResource> resources) {
        addLockedResources(resources);
    }
    
    @Exported
    public Set<BuildActionData> getBuildData() {
        return Collections.unmodifiableSet(buildData);
    }
    
    @DataBoundSetter
    public void setBuildData(Set<BuildActionData> buildData) {
        this.buildData.clear();
        this.buildData.addAll(buildData);
    }
    
    @Deprecated
    @DataBoundSetter
    public void setLockedResources(Set<LockableResource> resources) {
        this.buildData.clear();
        addLockedResources(resources);
    }
    
    public void addLockedResources(Set<LockableResource> resources) {
        for(LockableResource resource : resources) {
            this.buildData.add(new BuildActionData(resource));
        }
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
    
    /**
     * Magically called when imported from XML file
     * Manage backward compatibility
     *
     * @return myself
     */
    public Object readResolve() {
        if(lockedResources != null) {
            buildData = new LinkedHashSet<>();
            Calendar noCal = Calendar.getInstance();
            noCal.clear();
            for(LockableResource resource : lockedResources) {
                buildData.add(new BuildActionData(resource, noCal));
            }
        }
        return this;
    }
}
