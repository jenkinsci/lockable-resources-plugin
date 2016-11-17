/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright 2016 Eb.                                                  *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import hudson.Extension;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;

@Extension
public class LockQueueListener extends QueueListener {
    @Override
    public void onLeft(Queue.LeftItem item) {
        // A task has been removed from Jenkins queue (cancelled, aborted, ...)
        if(item.isCancelled()) {
            // => remove the task from lockable resources queue
            LockableResourcesManager manager = LockableResourcesManager.get();
            manager.removeFromLockQueue(item);
        }
    }
}
