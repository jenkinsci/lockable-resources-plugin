/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2015, SAP SE                                          *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import hudson.model.AbstractBuild;
import hudson.model.StringParameterValue;
import hudson.tasks.BuildWrapper;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 *
 */
public class RequiredResourcesParameterValue extends StringParameterValue {

	@DataBoundConstructor
	public RequiredResourcesParameterValue(String name, String value) {
		super(name, value);
	}

	@Override
	public BuildWrapper createBuildWrapper(AbstractBuild<?, ?> build) {
		return null;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + ((value == null) ? 0 : value.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if ( obj == null ) return false;
		if (getClass() != obj.getClass()) return false;
		RequiredResourcesParameterValue other = (RequiredResourcesParameterValue) obj;
		return this.value.equals(other.value);
	}

	@Override
	public String toString() {
		return "(RequiredResourcesParameterValue) " + getName() + "='" + value + "'";
	}
}
