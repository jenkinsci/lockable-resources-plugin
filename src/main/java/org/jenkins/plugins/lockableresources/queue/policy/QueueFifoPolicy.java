/*
 * The MIT License
 *
 * Copyright 2016 Eb.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkins.plugins.lockableresources.queue.policy;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Queue;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.queue.context.QueueContext;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 *
 * @author Eb
 */
@Extension
public class QueueFifoPolicy extends QueuePolicy {
    private static final Comparator<QueueContext> FIFO_COMPARATOR = new Comparator<QueueContext>() {
        @Override
        public int compare(QueueContext o1, QueueContext o2) {
            long inQueueSince1 = 0;
            long inQueueSince2 = 0;
            Run<?, ?> build = o1.getBuild();
            if(build == null) {
                Queue.Item item1 = Jenkins.getInstance().getQueue().getItem(o1.getQueueId());
                if(item1 != null) {
                    inQueueSince1 = item1.getInQueueSince();
                }
            } else {
                inQueueSince1 = build.getTimeInMillis();
            }
            build = o2.getBuild();
            if(build == null) {
                Queue.Item item2 = Jenkins.getInstance().getQueue().getItem(o2.getQueueId());
                if(item2 != null) {
                    inQueueSince2 = item2.getInQueueSince();
                }
            } else {
                inQueueSince2 = build.getTimeInMillis();
            }
            if(inQueueSince1 < inQueueSince2) {
                return -1;
            } else if(inQueueSince1 > inQueueSince2) {
                return 1;
            } else {
                return 0;
            }
        }
    };

    @DataBoundConstructor
    public QueueFifoPolicy() {
    }

    @Override
    public List<QueueContext> sort(@Nonnull Collection<QueueContext> queuedStructs) {
        ArrayList<QueueContext> res = new ArrayList<>(queuedStructs);
        Collections.sort(res, FIFO_COMPARATOR);
        return res;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<QueuePolicy> {
        @Override
        public String getDisplayName() {
            return "First queued, first selected";
        }
    }
}
