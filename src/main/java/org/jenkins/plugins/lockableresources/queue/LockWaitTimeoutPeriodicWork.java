/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.queue;

import hudson.Extension;
import hudson.model.PeriodicWork;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;

/**
 * Periodic work that checks for timed-out lock queue entries.
 *
 * <p>This runs every 15 seconds to check if any queued pipeline contexts have exceeded
 * their {@code timeoutForAllocateResource}. Without this, timed-out entries would only
 * be detected when resources are freed (which might never happen if all resources are
 * permanently busy).
 */
@Extension
public class LockWaitTimeoutPeriodicWork extends PeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(LockWaitTimeoutPeriodicWork.class.getName());

    @Override
    public long getRecurrencePeriod() {
        return 15_000L; // 15 seconds
    }

    @Override
    protected void doRun() {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        if (lrm.getCurrentQueuedContext().isEmpty()) {
            return;
        }
        LOGGER.log(Level.FINEST, "Checking for timed-out lock queue entries");
        lrm.checkTimeouts();
    }
}
