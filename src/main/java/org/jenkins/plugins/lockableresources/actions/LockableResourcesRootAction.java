/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.actions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.Messages;

import hudson.Extension;
import hudson.model.RootAction;
import hudson.model.User;
import hudson.security.AccessDeniedException2;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;

import javax.servlet.ServletException;

@Extension
public class LockableResourcesRootAction implements RootAction {

	private static final PermissionGroup PERMISSIONS_GROUP = new PermissionGroup(
					LockableResourcesManager.class, Messages._PermissionGroup());

	private static final Permission UNLOCK = new Permission(PERMISSIONS_GROUP,
					Messages.UnlockPermission(),
					Messages._UnlockPermission_Description(), Jenkins.ADMINISTER,
					PermissionScope.JENKINS);

	private static final Permission RESERVE = new Permission(PERMISSIONS_GROUP,
					Messages.ReservePermission(),
					Messages._ReservePermission_Description(), Jenkins.ADMINISTER,
					PermissionScope.JENKINS);

	static final String ICON = "/plugin/lockable-resources/img/device-24x24.png";

	public String getIconFileName() {
		if (User.current() != null) {
			// only show if logged in
			return ICON;
		} else {
			return null;
		}
	}

	public String getUserName() {
		User user = User.current();

		if (user != null) {
			return user.getFullName();
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

	public int getFreeResourceAmount(String label) {
		return LockableResourcesManager.get().getFreeResourceAmount(label);
	}

	public Set<String> getAllLabels() {
		return LockableResourcesManager.get().getAllLabels();
	}

	public int getNumberOfAllLabels() {
		return LockableResourcesManager.get().getAllLabels().size();
	}

	public void doUnlock(StaplerRequest req, StaplerResponse rsp)
					throws IOException, ServletException {
		Jenkins.getInstance().checkPermission(UNLOCK);

		String name = req.getParameter("resource");
		LockableResource r = LockableResourcesManager.get().fromName(name);
		if (r == null) {
			rsp.sendError(404, "Resource not found " + name);
			return;
		}

		List<LockableResource> resources = new ArrayList<>();
		resources.add(r);
		LockableResourcesManager.get().unlock(resources, null, null);

		rsp.forwardToPreviousPage(req);
	}

	public void doReserve(StaplerRequest req, StaplerResponse rsp)
					throws IOException, ServletException {
		Jenkins.getInstance().checkPermission(RESERVE);

		String name = req.getParameter("resource");
		LockableResource r = LockableResourcesManager.get().fromName(name);
		if (r == null) {
			rsp.sendError(404, "Resource not found " + name);
			return;
		}

		List<LockableResource> resources = new ArrayList<>();
		resources.add(r);
		String userName = getUserName();
		if (userName != null)
			LockableResourcesManager.get().reserve(resources, userName);

		rsp.forwardToPreviousPage(req);
	}

	public void doUnreserve(StaplerRequest req, StaplerResponse rsp)
					throws IOException, ServletException {
		Jenkins.getInstance().checkPermission(RESERVE);

		String name = req.getParameter("resource");
		LockableResource r = LockableResourcesManager.get().fromName(name);
		if (r == null) {
			rsp.sendError(404, "Resource not found " + name);
			return;
		}

		String userName = getUserName();
		if ((userName == null || !userName.equals(r.getReservedBy()))
						&& !Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER))
			throw new AccessDeniedException2(Jenkins.getAuthentication(),
							RESERVE);

		List<LockableResource> resources = new ArrayList<>();
		resources.add(r);
		LockableResourcesManager.get().unreserve(resources);

		rsp.forwardToPreviousPage(req);
	}

	public void doReset(StaplerRequest req, StaplerResponse rsp)
					throws IOException, ServletException {
		Jenkins.getInstance().checkPermission(UNLOCK);

		String name = req.getParameter("resource");
		LockableResource r = LockableResourcesManager.get().fromName(name);
		if (r == null) {
			rsp.sendError(404, "Resource not found " + name);
			return;
		}

		List<LockableResource> resources = new ArrayList<>();
		resources.add(r);
		LockableResourcesManager.get().reset(resources);

		rsp.forwardToPreviousPage(req);
	}

}
