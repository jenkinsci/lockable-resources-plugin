/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkinsci.plugins.workflow.steps.StepContext;

/**
 * This class migrates "active" queuedContexts from LockableResource to LockableResourcesManager
 *
 * @deprecated Migration code for field introduced in 1.8 (since 1.11)
 */
@Deprecated
@ExcludeFromJacocoGeneratedReport
public final class BackwardCompatibility {
    private static final Logger LOG = Logger.getLogger(BackwardCompatibility.class.getName());

    private BackwardCompatibility() {}

    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void compatibilityMigration() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        LockableResourcesManager lrm = GlobalConfiguration.all().get(LockableResourcesManager.class);
        if (lrm == null) {
            lrm = jenkins.getDescriptorByType(LockableResourcesManager.class);
        }
        if (lrm == null) {
            LOG.fine("Skipping queuedContexts migration because LockableResourcesManager is not registered yet");
            return;
        }
        synchronized (LockableResourcesManager.syncResources) {
            List<LockableResource> resources = lrm.getResources();
            LOG.log(
                    Level.FINE,
                    "lockable-resources-plugin compatibility migration task run for " + resources.size()
                            + " resources");
            for (LockableResource resource : resources) {
                List<StepContext> queuedContexts = resource.getQueuedContexts();
                if (!queuedContexts.isEmpty()) {
                    for (StepContext queuedContext : queuedContexts) {
                        List<String> resourcesNames = new ArrayList<>();
                        resourcesNames.add(resource.getName());
                        LockableResourcesStruct resourceHolder = new LockableResourcesStruct(resourcesNames, "", 0);
                        lrm.queueContext(
                                queuedContext,
                                Collections.singletonList(resourceHolder),
                                resource.getName(),
                                null,
                                false,
                                0);
                    }
                    queuedContexts.clear();
                }
            }
        }
    }
}
