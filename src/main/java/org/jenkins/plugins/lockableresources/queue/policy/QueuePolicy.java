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

import hudson.DescriptorExtensionList;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import java.util.Collection;
import java.util.List;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.queue.context.QueueContext;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.jenkins.plugins.lockableresources.resources.RequiredResources;
import org.jenkins.plugins.lockableresources.resources.selector.ResourcesSelector;

/**
 *
 * @author Eb
 */
public abstract class QueuePolicy extends AbstractDescribableImpl<QueuePolicy> {
    @Nonnull
    public abstract List<QueueContext> sort(@Nonnull Collection<QueueContext> queuedContexts);
    
    @CheckForNull
    public QueueContext select(@Nonnull Collection<QueueContext> queuedStructs, @Nonnull Collection<LockableResource> resources, @Nonnull ResourcesSelector selector) {
        for(QueueContext context : sort(queuedStructs)) {
            Collection<RequiredResources> requiredResources = context.getRequiredResources();
            if(requiredResources != null) {
                if(selector.selectFreeResources(resources, context) != null) {
                    return context;
                }
            }
        }
        return null;
    }
    
    @Nonnull
    public static DescriptorExtensionList<QueuePolicy, Descriptor<QueuePolicy>> all() {
        return Jenkins.getInstance().getDescriptorList(QueuePolicy.class);
    }
}
