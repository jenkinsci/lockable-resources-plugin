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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sometimes after re-starts (jenkins crashed or what ever) are resources still locked by build, but
 * the build is no more running. This script will 'unlock' all resource assigned to dead builds
 */
@ExcludeFromJacocoGeneratedReport
public final class FreeDeadJobs {
    private static final Logger LOG = Logger.getLogger(FreeDeadJobs.class.getName());

    private FreeDeadJobs() {}

    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void freePostMortemResources() {

        LockableResourcesManager lrm = LockableResourcesManager.get();
        synchronized (lrm) {
            LOG.log(Level.FINE, "lockable-resources-plugin free post mortem task run");
            for (LockableResource resource : lrm.getResources()) {
                if (resource.getBuild() != null && !resource.getBuild().isInProgress()) {
                    LOG.log(
                            Level.INFO,
                            "lockable-resources-plugin reset resource "
                                    + resource.getName()
                                    + " due post mortem job: "
                                    + resource.getBuildName());
                    resource.recycle();
                }
            }
        }
    }
}
