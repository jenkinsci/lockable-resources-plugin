/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.actions;

import java.util.ArrayList;
import java.util.List;

import org.jenkins.plugins.lockableresources.LockableResource;

import hudson.model.Action;

public class LockedResourcesBuildAction implements Action {

	private final List<ResourcePOJO> lockedResources;

	public LockedResourcesBuildAction(List<ResourcePOJO> lockedResources) {
		this.lockedResources = lockedResources;
	}

	public List<ResourcePOJO> getLockedResources() {
		return lockedResources;
	}

	public String getIconFileName() {
		return LockableResourcesRootAction.ICON;
	}

	public String getDisplayName() {
		return "Locked Resources";
	}

	public String getUrlName() {
		return "locked-resources";
	}

	public static LockedResourcesBuildAction fromResources(
					List<LockableResource> resources) {
		List<ResourcePOJO> lockedResources = new ArrayList<>();

		for (LockableResource resource : resources) {
			lockedResources.add(new ResourcePOJO(resource));
		}

		return new LockedResourcesBuildAction(lockedResources);
	}

	public static class ResourcePOJO {

		public String name;
		public String description;

		public ResourcePOJO(String name, String description) {
			this.name = name;
			this.description = description;
		}

		public ResourcePOJO(LockableResource resource) {
			this.name = resource.getName();
			this.description = resource.getDescription();
		}

	}

}
