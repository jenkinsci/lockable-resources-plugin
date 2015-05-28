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
import hudson.Util;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.ManagementLink;
import hudson.util.FormValidation;
import java.io.IOException;
import java.util.Set;
import javax.servlet.ServletException;
import jenkins.model.Jenkins;

import static org.jenkins.plugins.lockableresources.Constants.*;

import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

@Extension
public class LockableResourcesConfig
		extends ManagementLink
		implements Describable<LockableResourcesConfig> {
	
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
		manager.configure(req, req.getSubmittedForm());
		rsp.sendRedirect("");
	}
	
	@Override
	public String getUrlName() {
		return "lockable-resources-manager";
	}
	
	@Override
	public String getIconFileName() {
		return ICON_LARGE;
	}

	@Override
	public Descriptor<LockableResourcesConfig> getDescriptor() {
		return Jenkins.getInstance().getDescriptorOrDie(getClass());
	}

	@Extension
	public static class DescriptorImpl extends Descriptor<LockableResourcesConfig> {

		@Override
		public String getDisplayName() {
			return null;
		}

		// TODO: Improve these methods so that they check the current contents of the form
		//       and not just the labels available in the current live configuration.

		public FormValidation doCheckLoadBalancingLabels(@QueryParameter String value) {
			value = Util.fixEmptyAndTrim(value);
			Set<String> curLabels = LockableResourcesManager.get().getAllLabels();
			if ( value != null ) {
				for ( String label : value.split(RESOURCES_SPLIT_REGEX) ) {
					if ( !curLabels.contains(label) ) {
						return FormValidation.warning("One or more labels is new or does not exist.");
					}
				}
			}
			return FormValidation.ok();
		}

		public AutoCompletionCandidates doAutoCompleteLoadBalancingLabels(@QueryParameter String value) {
			AutoCompletionCandidates c = new AutoCompletionCandidates();
			value = Util.fixEmptyAndTrim(value);
			if (value != null) {
				for (String l : LockableResourcesManager.get().getAllLabels()) {
					if ( l.startsWith(value) ) c.add(l);
				}
			}
			return c;
		}
	}
}
