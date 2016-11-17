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
package org.jenkins.plugins.lockableresources.resources.selector;

import hudson.Extension;
import hudson.model.Descriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nonnull;
import org.jenkins.plugins.lockableresources.queue.context.QueueContext;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 *
 * @author Eb
 */
public class ResourcesDefaultSelector extends ResourcesSelector {
    @DataBoundConstructor
    public ResourcesDefaultSelector() {
    }
    
    /**
     * Select in priority the resources with exclusive use (reservation)
     * 
     * @param resources
     * @param queueContext
     * @return 
     */
    @Override
    public List<LockableResource> sortResources(@Nonnull Collection<LockableResource> resources, @Nonnull QueueContext queueContext) {
        String userId = queueContext.getUserId();
        List<LockableResource> res = new ArrayList<>();
        List<LockableResource> prio2 = new ArrayList<>();
        for(LockableResource r : resources) {
            if(r.hasExclusiveUse(userId)) {
                res.add(r);
            } else {
                prio2.add(r);
            }
        }
        res.addAll(prio2);
        return res;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ResourcesSelector> {
        @Override
        public String getDisplayName() {
            return "Basic resources selection";
        }
    }
}
