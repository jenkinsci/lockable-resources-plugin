/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013-2015, 6WIND S.A.                                 *
 *                          SAP SE                                     *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.actions;

import hudson.model.AbstractBuild;
import hudson.model.Action;

import java.util.ArrayList;
import java.util.List;

import static org.jenkins.plugins.lockableresources.Constants.*;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;

public class LockedResourcesBuildAction implements Action {

	private final List<ResourcePOJO> lockedResources = new ArrayList<ResourcePOJO>();

	public final transient List<String> matchedResources = new ArrayList<String>();

	public List<ResourcePOJO> getLockedResources() {
		return lockedResources;
	}

	public String getIconFileName() {
		return ICON_SMALL;
	}

	public String getDisplayName() {
		return "Locked Resources";
	}

	public String getUrlName() {
		return "locked-resources";
	}

	public void populateLockedResources( AbstractBuild<?, ?> build ) {
		LockableResourcesManager manager = LockableResourcesManager.get();
		lockedResources.clear();
		for ( String rName : matchedResources ) {
			LockableResource r = manager.fromName(rName);
			assert(r.getBuild() == build);
			lockedResources.add(new ResourcePOJO(r));
		}
	}

	public static class ResourcePOJO {

		public final String name;
		public final String description;

		public ResourcePOJO(String name, String description) {
			this.name = name;
			this.description = description;
		}

		public ResourcePOJO(LockableResource r) {
			this.name = r.getName();
			this.description = r.getDescription();
		}

	}

}
