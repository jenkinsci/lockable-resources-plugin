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

import groovy.lang.Tuple2;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Descriptor;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import org.jenkins.plugins.lockableresources.queue.context.QueueContext;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.jenkins.plugins.lockableresources.resources.ResourceCapability;
import org.kohsuke.stapler.DataBoundConstructor;

/** If this option is selected, the plugin will use an internal algorithm to select
 * the free resources based on their capabilities.<br>
 * The resource that has a unique capability among all other resources has less chance
 * to be selected.<br>
 * On the contrary, if a free resource has very common capabilities it will probably be selected
 * <p>
 * This Selector is highly experimental.
 *
 * @author Eb
 */
public class ResourcesFairSelector extends ResourcesDefaultSelector {
    private static final Logger LOGGER = Logger.getLogger(ResourcesFairSelector.class.getName());

    private static class SortComparator implements Comparator<Tuple2<LockableResource, Double>>, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public int compare(Tuple2<LockableResource, Double> o1, Tuple2<LockableResource, Double> o2) {
            return o1.getSecond().compareTo(o2.getSecond());
        }
    }
    private static final SortComparator COMPARATOR = new SortComparator();

    @DataBoundConstructor
    public ResourcesFairSelector() {
    }

    @Override
    public List<LockableResource> sortResources(@Nonnull Collection<LockableResource> resources, @Nonnull QueueContext queueContext) {
        // Extract the best resources based on their capabilities
        List<Tuple2<LockableResource, Double>> sorted = new ArrayList<>(); // (resource, cost)
        EnvVars env = queueContext.getEnvVars();
        for(LockableResource r : resources) {
            Set<ResourceCapability> rc = r.getCapabilities();
            Set<LockableResource> compatibles = ResourceCapability.getResourcesFromCapabilities(resources, rc, null, env);
            int nFree = getFreeResources(compatibles, queueContext).size();
            int nMax = compatibles.size(); // >=1
            double cost = (nMax - nFree) * (resources.size() / nMax) + rc.size();
            sorted.add(new Tuple2<>(r, cost));
        }
        Collections.sort(sorted, COMPARATOR);
        LOGGER.finer("Costs for using resources:");
        ArrayList<LockableResource> res = new ArrayList<>();
        for(Tuple2<LockableResource, Double> t : sorted) {
            res.add(t.getFirst());
            LOGGER.info(" - " + t.getFirst().getName() + ": " + t.getSecond());
        }
        return super.sortResources(res, queueContext);
    }
    
    @Extension
    public static class DescriptorImpl extends Descriptor<ResourcesSelector> {
        @Override
        public String getDisplayName() {
            return "Fair resources selection (experimental)";
        }
    }
}
