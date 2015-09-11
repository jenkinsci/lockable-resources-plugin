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

import hudson.Extension;
import hudson.model.Api;
import hudson.model.RootAction;
import hudson.model.User;
import hudson.security.AccessDeniedException2;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;

import static org.jenkins.plugins.lockableresources.Constants.*;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.Messages;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

@Extension
@ExportedBean
public class LockableResourcesRootAction implements RootAction {

	public static final PermissionGroup PERMISSIONS_GROUP = new PermissionGroup(
			LockableResourcesManager.class, Messages._PermissionGroup());
	public static final Permission UNLOCK = new Permission(PERMISSIONS_GROUP,
			Messages.UnlockPermission(),
			Messages._UnlockPermission_Description(), Jenkins.ADMINISTER,
			PermissionScope.JENKINS);
	public static final Permission RESERVE = new Permission(PERMISSIONS_GROUP,
			Messages.ReservePermission(),
			Messages._ReservePermission_Description(), Jenkins.ADMINISTER,
			PermissionScope.JENKINS);

	public String getIconFileName() {
		if (User.current() != null) {
			// only show if logged in
			return ICON_SMALL;
		} else {
			return null;
		}
	}

	public String getUserName() {
		if (User.current() != null)
			return User.current().getFullName();
		else
			return null;
	}

	public String getDisplayName() {
		return "Lockable Resources";
	}

	public String getUrlName() {
		return "lockable-resources";
	}

	public Api getApi() {
		return new Api(this);
	}

	@Exported
	public Collection<LockableResource> getResources() {
		return Collections.unmodifiableCollection(LockableResourcesManager.get().getResources());
	}

	public int getFreeResourceAmount(String label) {
		return LockableResourcesManager.get().getFreeResourceAmount(label);
	}

	public String dereferenceLabelAlias(String label) {
		return LockableResourcesManager.get().dereferenceLabelAlias(label);
	}

	public Set<String> getAllLabels() {
		return Collections.unmodifiableSet(LockableResourcesManager.get().getAllLabels());
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

		List<LockableResource> resources = new ArrayList<LockableResource>();
		resources.add(r);
		LockableResourcesManager.get().unlock(resources, null);

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

		List<LockableResource> resources = new ArrayList<LockableResource>();
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

		List<LockableResource> resources = new ArrayList<LockableResource>();
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

		List<LockableResource> resources = new ArrayList<LockableResource>();
		resources.add(r);
		LockableResourcesManager.get().reset(resources);

		rsp.forwardToPreviousPage(req);
	}
}
