/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, Aki Asikainen. All rights reserved.             *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import hudson.EnvVars;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;

public class LockableResourcesStruct implements Serializable {

	public List<LockableResource> required;
	public String label;
	public String requiredVar;
	public String requiredNumber;

	public LockableResourcesStruct(RequiredResourcesProperty property,
			EnvVars env) {
		required = new ArrayList<>();
		for (String name : property.getResources()) {
			LockableResource r = LockableResourcesManager.get().fromName(
				env.expand(name));
			if (r != null) {
				this.required.add(r);
			}
		}

		label = env.expand(property.getLabelName());
		if (label == null)
			label = "";

		requiredVar = property.getResourceNamesVar();

		requiredNumber = property.getResourceNumber();
		if (requiredNumber != null && requiredNumber.equals("0"))
			requiredNumber = null;
	}

	public LockableResourcesStruct(@Nullable List<String> resources) {
		this(resources, null, 0);
	}

	public LockableResourcesStruct(@Nullable List<String> resources, @Nullable String label, int quantity) {
		required = new ArrayList<>();
		if (resources != null) {
			for (String resource : resources) {
				LockableResource r = LockableResourcesManager.get().fromName(resource);
				if (r != null) {
					this.required.add(r);
				}
			}
		}

		this.label = label;
		if (this.label == null) {
		    this.label = "";
		}

		this.requiredNumber = null;
		if (quantity > 0) {
			this.requiredNumber = String.valueOf(quantity);
		}
	}

	@Override
	public String toString() {
		return "Required resources: " + this.required +
			", Required label: " + this.label +
			", Variable name: " + this.requiredVar +
			", Number of resources: " + this.requiredNumber;
	}

	private static final long serialVersionUID = 1L;
}
