/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, Aki Asikainen. All rights reserved.             *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

import org.jenkins.plugins.lockableresources.RequiredResources;

import com.google.common.base.Strings;

import hudson.EnvVars;

public class LockableResourcesStruct implements Serializable {

	private static final long serialVersionUID = 1L;

	public List<String> resourceNames;
	public String label;
	public String requiredVar;
	public String requiredNumber;

	public LockableResourcesStruct(List<String> resourceNames) {
		this.resourceNames = resourceNames;
	}

	public LockableResourcesStruct(RequiredResources requiredResources, EnvVars env) {
		resourceNames = Arrays.asList(requiredResources.getResources());

		label = env.expand(requiredResources.getLabelName());

		if (label == null) {
			label = "";
		}

		requiredVar = requiredResources.getResourceNamesVar();
		requiredNumber = requiredResources.getResourceNumber();

		if (Strings.isNullOrEmpty(requiredNumber) || "0".equals(requiredNumber)) {
			requiredNumber = null;
		}
	}

	public String toString() {
		return "Required resources: " + this.resourceNames +
						", Required label: " + this.label +
						", Variable name: " + this.requiredVar +
						", Number of resources: " + this.requiredNumber;
	}

}
