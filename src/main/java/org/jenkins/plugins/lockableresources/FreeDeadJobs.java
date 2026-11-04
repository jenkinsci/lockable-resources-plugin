/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;

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
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        LockableResourcesManager lrm = GlobalConfiguration.all().get(LockableResourcesManager.class);
        if (lrm == null) {
            lrm = jenkins.getDescriptorByType(LockableResourcesManager.class);
        }
        if (lrm == null) {
            LOG.fine("Skipping post mortem resource cleanup because LockableResourcesManager is not registered yet");
            return;
        }
        boolean freedAny = false;
        synchronized (LockableResourcesManager.syncResources) {
            List<LockableResource> orphan = new ArrayList<>();
            LOG.log(Level.FINE, "lockable-resources-plugin free post mortem task run");
            for (LockableResource resource : lrm.getResources()) {
                // a finished build legitimately holds the resource until its release delay is over
                if (resource.isReleaseDelayed()) {
                    continue;
                }
                if (resource.getBuild() != null && !resource.getBuild().isInProgress()) {
                    orphan.add(resource);
                }
            }

            for (LockableResource resource : orphan) {
                LOG.log(
                        Level.INFO,
                        "lockable-resources-plugin reset resource "
                                + resource.getName()
                                + " due post mortem job: "
                                + resource.getBuildName());
                resource.recycle();
                freedAny = true;
            }
        }
        if (freedAny) {
            LockableResourcesManager.scheduleQueueMaintenance();
        }
    }

    /**
     * Release delays (lock step onReleaseDelay / onCancelDelay) are persisted on the resources. After a
     * restart, release the ones that ended while Jenkins was down and schedule the pending ones again.
     * Runs after all jobs are loaded (the last milestone before COMPLETED; an initializer must not
     * run after COMPLETED, see JENKINS-37759), so the waiting pipelines that get the resources can resume.
     */
    @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED)
    public static void resumeDelayedReleases() {
        if (Jenkins.getInstanceOrNull() == null) {
            return;
        }
        LockableResourcesManager lrm = GlobalConfiguration.all().get(LockableResourcesManager.class);
        if (lrm == null) {
            LOG.fine("Skipping delayed releases because LockableResourcesManager is not registered yet");
            return;
        }
        lrm.releaseDueResources();
    }
}
