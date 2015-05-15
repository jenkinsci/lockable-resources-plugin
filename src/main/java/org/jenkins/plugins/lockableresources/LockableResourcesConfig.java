/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013-2015, 6WIND S.A.                                 *
 *                          SAP SE                                     *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import hudson.Extension;
import hudson.model.ManagementLink;
import java.io.IOException;
import javax.servlet.ServletException;

import org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

@Extension
public class LockableResourcesConfig extends ManagementLink {
	
	private final LockableResourcesManager manager;
	
	public LockableResourcesConfig() {
		manager = LockableResourcesManager.get();
	}

	@Override
	public String getDisplayName() {
		return "Lockable Resources Manager";
	}
	
	@Override
	public String getDescription() {
		return "Define lockable resources (such as printers, phones, computers) that can be used by builds.";
	}
	
	public LockableResourcesManager getManager() {
		return manager;
	}

    @RequirePOST
    public void doConfigSubmit(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        if ( manager.configure(req, req.getSubmittedForm()) ) {
			rsp.sendRedirect("");
		}
		else {
			rsp.sendError(500);
		}
    }
	
    @Override
    public String getUrlName() {
        return "lockable-resources-manager";
    }
	
	@Override
	public String getIconFileName() {
		return LockableResourcesRootAction.ICON;
	}
}
