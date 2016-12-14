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
import java.util.List;

import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkins.plugins.lockableresources.queue.QueuedContextStruct;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;

import java.util.logging.Logger;

public final class BackwardCompatibility {
    private static final Logger LOG = Logger.getLogger(BackwardCompatibility.class
                                            .getName());

    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void beforeInitMilestonePluginsStarted() {
        LOG.fine("\n\n LOCKABLE RESOURCES COMPATABILITY INIT \n\n");
		List<LockableResource> resources = LockableResourcesManager.get().getResources();
		for (LockableResource resource : resources) {
			List<StepContext> queuedContexts = resource.getQueuedContexts();
			if (queuedContexts.size() > 0) {
				for (StepContext queuedContext : queuedContexts) {
					List<String> resourcesNames = new ArrayList<String>();
					resourcesNames.add(resource.getName());
					LockableResourcesStruct resourceHolder = new LockableResourcesStruct(resourcesNames, "", 0);
					LockableResourcesManager.get().queueContext(queuedContext, resourceHolder, resource.getName());
				}
				queuedContexts.clear();
			}
		}
    }
}