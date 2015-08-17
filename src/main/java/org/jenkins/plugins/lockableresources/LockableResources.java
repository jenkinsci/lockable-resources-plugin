/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2015, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import hudson.Plugin;
import hudson.model.Api;

import java.util.Collections;
import java.util.Set;

import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

@ExportedBean
public class LockableResources extends Plugin {

	public Api getApi() {
		return new Api(this);
	}

	/**
	 * @return A resources list that can be accessed by a remote API
	 */
	@Exported
	public Set<LockableResource> getResources() {
		return Collections.unmodifiableSet(LockableResourcesManager.get()
				.getResources());
	}

}
