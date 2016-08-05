/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import java.util.List;

import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

public class RequiredResourcesProperty extends JobProperty<Job<?, ?>> {

	@CheckForNull
	private final List<RequiredResources> requiredResourcesList;

	@DataBoundConstructor
	public RequiredResourcesProperty(List<RequiredResources> requiredResourcesList) {
		super();
		this.requiredResourcesList = Util.fixNull(requiredResourcesList);
	}

	@Nonnull
	public List<RequiredResources> getRequiredResourcesList() {
		return Util.fixNull(requiredResourcesList);
	}

	@Extension
	public static class DescriptorImpl extends JobPropertyDescriptor {

		@Override
		public String getDisplayName() {
			return "Required Lockable Resources List";
		}

		@Override
		public boolean isApplicable(Class<? extends Job> jobType) {
			return AbstractProject.class.isAssignableFrom(jobType);
		}

		@Override
		public RequiredResourcesProperty newInstance(StaplerRequest req,
																								 JSONObject formData) throws FormException {
			if (formData.isNullObject()) {
				return null;
			}

			JSONObject json = formData.getJSONObject("required-lockable-resources");

			if (json.isNullObject()) {
				return null;
			}

			List<RequiredResources> requiredResourcesList = req.bindJSONToList(
							RequiredResources.class, json.get("requiredResourcesList"));

			return new RequiredResourcesProperty(requiredResourcesList);
		}

	}

}

