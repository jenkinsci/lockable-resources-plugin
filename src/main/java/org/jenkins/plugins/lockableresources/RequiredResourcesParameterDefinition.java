/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2015, SAP SE                                          *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import hudson.Extension;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

/**
 *
 */
public class RequiredResourcesParameterDefinition extends StringParameterDefinition {

	@DataBoundConstructor
	public RequiredResourcesParameterDefinition(String name, String defaultValue, String description) {
		super(name, defaultValue, description);
	}

	public RequiredResourcesParameterDefinition(String name, String defaultValue) {
		this(name, defaultValue, null);
	}

	@Extension
	public static class DescriptorImpl extends ParameterDescriptor {
		@Override
		public String getDisplayName() {
			return Messages.RequiredResourcesParameterDefinition_DisplayName();
		}
	}

	@Override
	public RequiredResourcesParameterValue createValue(String value) {
		return new RequiredResourcesParameterValue(getName(), value);
	}

	@Override
	public RequiredResourcesParameterValue createValue(StaplerRequest req, JSONObject jo) {
		RequiredResourcesParameterValue value = req.bindJSON(RequiredResourcesParameterValue.class, jo);
		return value;
	}

	@Override
	public RequiredResourcesParameterValue getDefaultParameterValue() {
		return createValue(getDefaultValue());
	}

	@Override
	public ParameterDefinition copyWithDefaultValue(ParameterValue defaultValue) {
		if (defaultValue instanceof StringParameterValue) {
			StringParameterValue value = (StringParameterValue) defaultValue;
			return new RequiredResourcesParameterDefinition(getName(), value.value, getDescription());
		}
		else {
			return this;
		}
	}
}
