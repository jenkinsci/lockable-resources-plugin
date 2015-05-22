/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013-2015, Aki Asikainen                              *
 *                          SAP SE                                     *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import java.util.LinkedHashSet;
import java.util.Set;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;

public class LockableResourcesStruct {

	public Set<LockableResource> required;
	public transient String requiredNames;
	public String requiredVar;
	public String requiredNumber;

	public LockableResourcesStruct(RequiredResourcesProperty property) {
		this.required = new LinkedHashSet<LockableResource>();
		this.requiredNames = property.getResourceNames();
		for (String name : property.getResources()) {
			LockableResource r = LockableResourcesManager.get().fromName(
				name);
			if (r != null) {
				this.required.add(r);
			}
			else {
				this.required.addAll(LockableResourcesManager.get().getResourcesWithLabel(name));
			}
		}

		this.requiredVar = property.getResourceNamesVar();
		if (this.requiredVar != null && this.requiredVar.equals("")) {
			this.requiredVar = null;
		}

		this.requiredNumber = property.getResourceNumber();
		if (this.requiredNumber != null && (this.requiredNumber.equals("") ||
			this.requiredNumber.trim().equals("0"))) {
			this.requiredNumber = null;
		}
	}

	public String toString() {
		return "Required resources: " + this.required +
			", Variable name: " + this.requiredVar +
			", Number of resources: " + this.requiredNumber;
	}
}
