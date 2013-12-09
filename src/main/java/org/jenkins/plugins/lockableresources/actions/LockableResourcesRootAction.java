/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.actions;

import hudson.Extension;
import hudson.model.RootAction;

import java.util.List;

import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.LockableResource;

@Extension
public class LockableResourcesRootAction implements RootAction {

	public static final String ICON = "/plugin/lockable-resources/img/device-24x24.png";

	public String getIconFileName() {
		return ICON;
	}

	public String getDisplayName() {
		return "Lockable Resources";
	}

	public String getUrlName() {
		return "lockable-resources";
	}

	public List<LockableResource> getResources() {
		return LockableResourcesManager.get().getResources();
	}

}
