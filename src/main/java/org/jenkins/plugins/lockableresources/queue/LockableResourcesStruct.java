/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, Aki Asikainen. All rights reserved.             *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import java.util.ArrayList;
import java.util.List;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;

public class LockableResourcesStruct {

	public List<LockableResource> required;
	public String label;
	public String requiredVar;
	public String requiredNumber;

	public LockableResourcesStruct(RequiredResourcesProperty property) {
		this.required = new ArrayList<LockableResource>();
		for (String name : property.getResources()) {
			LockableResource r = LockableResourcesManager.get().fromName(
				name);
			if (r != null) {
				this.required.add(r);
			}
		}
		
		this.label = property.getLabelName();
		if (this.label == null)
			this.label = "";
		
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
			", Required label: " + this.label +
			", Variable name: " + this.requiredVar +
			", Number of resources: " + this.requiredNumber;
	}
}
