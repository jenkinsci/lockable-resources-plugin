/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2016, Florian Hug. All rights reserved.             *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

package org.jenkins.plugins.lockableresources;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkinsci.plugins.workflow.steps.StepContext;

/**
 * This class migrates "active" queuedContexts from LockableResource to LockableResourcesManager
 *
 * @deprecated Migration code for field introduced in 1.8 (since 1.11)
 */
@Deprecated
public final class BackwardCompatibility {
	private static final Logger LOG = Logger.getLogger(BackwardCompatibility.class.getName());

	private BackwardCompatibility() {}

	@Initializer(after = InitMilestone.JOB_LOADED)
	public static void compatibilityMigration() {
		LOG.log(Level.FINE, "lockable-resource-plugin compatibility migration task run");
		List<LockableResource> resources = LockableResourcesManager.get().getResources();
		for (LockableResource resource : resources) {
			List<StepContext> queuedContexts = resource.getQueuedContexts();
			if (!queuedContexts.isEmpty()) {
				for (StepContext queuedContext : queuedContexts) {
					List<String> resourcesNames = new ArrayList<>();
					resourcesNames.add(resource.getName());
					LockableResourcesStruct resourceHolder = new LockableResourcesStruct(resourcesNames, "", 0);
					LockableResourcesManager.get().queueContext(queuedContext, Arrays.asList(resourceHolder), resource.getName(), null);
				}
				queuedContexts.clear();
			}
		}
	}
}
