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
import hudson.model.User;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;

import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

@Extension
public class LockableResourcesRootAction implements RootAction {

	public static final String ICON = "/plugin/lockable-resources/img/device-24x24.png";

	public String getIconFileName() {
		if (User.current() != null) {
			// only show if logged in
			return ICON;
		} else {
			return null;
		}
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

	public void doUnlock(StaplerRequest req, StaplerResponse rsp)
			throws IOException, ServletException {
		Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

		String name = req.getParameter("resource");
		LockableResource r = LockableResourcesManager.get().fromName(name);
		if (r == null) {
			rsp.sendError(404, "Resource not found " + name);
			return;
		}

		List<LockableResource> resources = new ArrayList<LockableResource>();
		resources.add(r);
		LockableResourcesManager.get().unlock(resources, null);

		rsp.forwardToPreviousPage(req);
	}
}
