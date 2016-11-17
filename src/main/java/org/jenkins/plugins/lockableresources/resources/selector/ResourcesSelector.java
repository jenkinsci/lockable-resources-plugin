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
import hudson.DescriptorExtensionList;
import hudson.EnvVars;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.queue.context.QueueContext;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.jenkins.plugins.lockableresources.resources.RequiredResources;
import org.jenkins.plugins.lockableresources.resources.ResourceCapability;

/**
 *
 * @author Eb
 */
public abstract class ResourcesSelector extends AbstractDescribableImpl<ResourcesSelector> {
    @Nonnull
    public abstract List<LockableResource> sortResources(@Nonnull Collection<LockableResource> resources, @Nonnull QueueContext queueContext);

    @CheckForNull
    public Set<LockableResource> selectResources(@Nonnull Collection<LockableResource> allResources, @Nonnull Collection<LockableResource> freeResources, @Nonnull QueueContext queueContext) {
        Set<LockableResource> res = new HashSet<>();
        Collection<RequiredResources> requiredResourcesList = queueContext.getRequiredResources();
        if(requiredResourcesList == null) {
            return res;
        }
        EnvVars env = queueContext.getEnvVars();
        //--------------
        // Add resources by names
        //--------------
        for(RequiredResources rr : requiredResourcesList) {
            Set<LockableResource> neededResources = rr.getResourcesList(env);
            if(neededResources == null) {
                // At least one invalid resource name
                return null;
            }
            if(!freeResources.containsAll(neededResources)) {
                // At least one unavailable resource
                return null;
            }
            res.addAll(neededResources);
        }
        //--------------
        // Add resources by capabilities + quantity
        //--------------
        List<Tuple2<Set<LockableResource>, Integer>> request = new ArrayList<>(requiredResourcesList.size());
        for(RequiredResources rr : requiredResourcesList) {
            Set<ResourceCapability> capabilities = rr.getCapabilitiesList(env);
            if(capabilities.size() > 0) {
                Set<LockableResource> candidates = ResourceCapability.getResourcesFromCapabilities(allResources, capabilities, null, env);
                candidates.removeAll(res); // Already selected by names: can not be re-use for capabilities selection
                int candidatesSize = candidates.size();
                candidates.retainAll(freeResources);
                if(rr.getQuantity() <= 0) {
                    // Use all resources of this type
                    if(candidates.size() != candidatesSize) {
                        // Not all resources available
                        return null;
                    }
                    res.addAll(candidates);
                } else {
                    request.add(new Tuple2<>(candidates, rr.getQuantity()));
                }
            }
        }
        Set<LockableResource> selected = selectAmongPossibilities(request);
        if(selected == null) {
            // No enough free resources with given capabilities
            return null;
        }
        // Merge resources by names and by capabilities
        res.addAll(selected);
        return res;
    }

    @Nonnull
    public Set<LockableResource> getFreeResources(@Nonnull Collection<LockableResource> resources, @Nonnull QueueContext queueContext) {
        HashSet<LockableResource> free = new HashSet<>();
        String userId = queueContext.getUserId();
        long queueId = queueContext.getQueueId();
        for(LockableResource r : resources) {
            if(r.canLock(userId, queueId)) {
                free.add(r);
            }
        }
        return free;
    }

    @CheckForNull
    public Set<LockableResource> selectFreeResources(@Nonnull Collection<LockableResource> resources, @Nonnull QueueContext queueContext) {
        return selectResources(resources, getFreeResources(resources, queueContext), queueContext);
    }

    /**
     * Brute force recursive algorithm to find a set of resources matching the request
     *
     * @param <T>
     * @param request
     *
     * @return
     */
    private static <T> Set<T> selectAmongPossibilities(@Nonnull List<Tuple2<Set<T>, Integer>> request) {
        //--------------------
        // Easy case: solution found
        //--------------------
        if(request.size() <= 0) {
            return new HashSet<>();
        }
        //--------------------
        // Select first sub-request
        // Try to select each candidate and perform recursive call with adapter request
        //--------------------
        Tuple2<Set<T>, Integer> subRequest = request.get(0);
        Set<T> candidates = subRequest.getFirst();
        int nb = subRequest.getSecond();
        if(nb <= 0) {
            // No resource needed: remove the sub-request and continue with others
            List<Tuple2<Set<T>, Integer>> newRequest = new ArrayList<>(request);
            newRequest.remove(subRequest);
            return selectAmongPossibilities(newRequest);
        } else if(nb <= candidates.size()) {
            // Select each candidate, remove it from all other pools in subrequests, then perform recursive call
            List<Tuple2<Set<T>, Integer>> newRequest = new ArrayList<>(request.size());
            for(T v : candidates) {
                newRequest.clear();
                for(Tuple2<Set<T>, Integer> sr : request) {
                    Set<T> newCandidates = new HashSet<>(sr.getFirst());
                    newCandidates.remove(v);
                    int newNb = ((sr == subRequest) ? nb - 1 : sr.getSecond());
                    newRequest.add(new Tuple2<>(newCandidates, newNb));
                }
                Set<T> res = selectAmongPossibilities(newRequest);
                if(res != null) {
                    res.add(v);
                    return res;
                }
            }
        }
        // No solution
        return null;
    }

    @Nonnull
    public static DescriptorExtensionList<ResourcesSelector, Descriptor<ResourcesSelector>> all() {
        return Jenkins.getInstance().getDescriptorList(ResourcesSelector.class);
    }
}
