/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.ListBoxModel;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import static org.jenkins.plugins.lockableresources.LockableResource.GROOVY_LABEL_MARKER;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class ResourceCapability extends AbstractDescribableImpl<ResourceCapability> implements Comparable<ResourceCapability> {
    private static final Logger LOGGER = Logger.getLogger(LockableResourcesParameterDefinition.class.getName());
    private String name;
    
    @DataBoundConstructor
    public ResourceCapability(String name) {
        this.name = getSafeName(name);
    }
    
    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = getSafeName(name);
    }
    
    @Override
    public int compareTo(ResourceCapability o) {
        return name.compareTo(o.name);
    }

    @Override
    public boolean equals(Object obj) {
        if(obj instanceof ResourceCapability) {
            return name.equals(((ResourceCapability)obj).name);
        }
        return false;
    }

    @Override
    public int hashCode() {
        int hash = 3;
        hash = 67 * hash + Objects.hashCode(this.name);
        return hash;
    }
    
    private static String getSafeName(String name) {
        return name.replace("\\s", "_");
    }
    
    public static String createLabel(Collection<ResourceCapability> capabilities) {
        StringBuilder sb = new StringBuilder();
        for (ResourceCapability s : capabilities) {
            sb.append(s.getName()).append(' ');
        }
        return sb.toString().trim();
    }
    
    public static Set<ResourceCapability> splitCapabilities(String labels) {
        Set<ResourceCapability> res = new HashSet<>();
        // Special case: the whole label may be a groovy script
        if((labels != null) && (! labels.startsWith(GROOVY_LABEL_MARKER))) {
            for(String label: labels.split("\\s+")) {
                res.add(new ResourceCapability(label));
            }
        }
        return res;
    }
    
    public static boolean hasAllCapabilities(Collection<ResourceCapability> capabilities, Collection<ResourceCapability> neededCapabilities) {
        if(neededCapabilities == null) {
            return true;
        }
        for(ResourceCapability capability: neededCapabilities) {
            if(! capabilities.contains(capability)) {
                return false;
            }
        }
        return true;
    }
    
    public static boolean hasNoneOfCapabilities(Collection<ResourceCapability> capabilities, Collection<ResourceCapability> forbiddenCapabilities) {
        if(forbiddenCapabilities == null) {
            return true;
        }
        for(ResourceCapability capability: capabilities) {
            if(forbiddenCapabilities.contains(capability)) {
                return false;
            }
        }
        return true;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ResourceCapability> {
        @Override
        public String getDisplayName() {
            return "LockableResourcesParameterDefinition.StrObj";
        }
        
        public ListBoxModel doFillNameItems(@QueryParameter boolean onlyResourceNames, @QueryParameter String neededLabels, @QueryParameter String prohibitedLabels) {
            LOGGER.fine("**** onlyResourceNames = " + onlyResourceNames + " ******");
            LOGGER.fine("**** neededLabels = " + neededLabels + " ******");
            LOGGER.fine("**** prohibitedLabels = " + prohibitedLabels + " ******");
            LockableResourcesManager manager = LockableResourcesManager.get();
            ListBoxModel res = new ListBoxModel();
            Set<ResourceCapability> neededCapabilities = ResourceCapability.splitCapabilities(neededLabels);
            Set<ResourceCapability> prohibitedCapabilities = ResourceCapability.splitCapabilities(prohibitedLabels);
            if(onlyResourceNames) {
                for(LockableResource r: manager.getResources()) {
                    if(r.hasCapabilities(neededCapabilities, prohibitedCapabilities)) {
                        res.add(r.getName());
                    }
                }
            } else {
                for(ResourceCapability capability: manager.getCapabilities(neededCapabilities, prohibitedCapabilities)) {
                    res.add(capability.name);
                }
            }
            return res;
        }
    }
}
