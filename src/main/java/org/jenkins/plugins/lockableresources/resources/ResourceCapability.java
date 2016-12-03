/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.resources;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.ListBoxModel;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.jobParameter.LockableResourcesParameterDefinition;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;

public class ResourceCapability extends AbstractDescribableImpl<ResourceCapability> implements Comparable<ResourceCapability> {
    private static final Logger LOGGER = Logger.getLogger(LockableResourcesParameterDefinition.class.getName());
    @Exported
    protected String name;

    @DataBoundConstructor
    public ResourceCapability(String name) {
        this.name = getSafeName(name);
    }

    @Exported
    @Nonnull
    public String getName() {
        return name;
    }

    @DataBoundSetter
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
            return name.equals(((ResourceCapability) obj).name);
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
        if(capabilities == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for(ResourceCapability s : capabilities) {
            if(sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s.getName());
        }
        return sb.toString().trim();
    }

    @Nonnull
    public static Set<ResourceCapability> splitCapabilities(@Nullable String label) {
        Set<String> labels = Utils.splitLabels(label);
        Set<ResourceCapability> res = new HashSet<>();
        for(String l : labels) {
            res.add(new ResourceCapability(l));
        }
        return res;
    }

    public static boolean hasAllCapabilities(Collection<ResourceCapability> capabilities, Collection<ResourceCapability> neededCapabilities) {
        if(neededCapabilities == null) {
            return true;
        }
        if(capabilities == null) {
            return (neededCapabilities.size() <= 0);
        }
        for(ResourceCapability capability : neededCapabilities) {
            if(!capabilities.contains(capability)) {
                LOGGER.finer("No resource with capability: " + capability.name);
                return false;
            }
        }
        return true;
    }

    public static boolean hasNoneOfCapabilities(Collection<ResourceCapability> capabilities, Collection<ResourceCapability> forbiddenCapabilities) {
        if((forbiddenCapabilities == null) || (capabilities == null)) {
            return true;
        }
        for(ResourceCapability capability : capabilities) {
            if(forbiddenCapabilities.contains(capability)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     *
     * @param resources
     * @param neededCapabilities
     * @param prohibitedCapabilities
     * @param env                    Used only for Groovy script execution
     *
     * @return
     */
    public static Set<LockableResource> getResourcesFromCapabilities(@Nonnull Collection<LockableResource> resources, @Nullable Collection<ResourceCapability> neededCapabilities, @Nullable Collection<ResourceCapability> prohibitedCapabilities, @Nullable EnvVars env) {
        LinkedHashSet<LockableResource> found = new LinkedHashSet<>(); // Keep resources order if possible
        for(LockableResource r : resources) {
            if(r.hasCapabilities(neededCapabilities, prohibitedCapabilities, env)) {
                found.add(r);
            }
        }
        return found;
    }

    /**
     *
     * @param resources
     * @param neededCapabilities
     * @param prohibitedCapabilities
     * @param env                    Used only for Groovy script execution
     *
     * @return
     */
    public static Set<ResourceCapability> getCompatibleCapabilities(@Nonnull Collection<LockableResource> resources, Collection<ResourceCapability> neededCapabilities, Collection<ResourceCapability> prohibitedCapabilities, @Nullable EnvVars env) {
        TreeSet<ResourceCapability> capabilities = new TreeSet<>();
        for(LockableResource r : resources) {
            if(r.hasCapabilities(neededCapabilities, prohibitedCapabilities, env)) {
                capabilities.addAll(r.getCapabilities());
                capabilities.add(r.getMyselfAsCapability());
            }
        }
        return capabilities;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ResourceCapability> {
        @Override
        public String getDisplayName() {
            return "LockableResourcesParameterDefinition.StrObj";
        }

        public static ListBoxModel doFillNameItems(@QueryParameter boolean onlyResourceNames, @QueryParameter String neededLabels, @QueryParameter String prohibitedLabels) {
            LOGGER.fine("**** onlyResourceNames = " + onlyResourceNames + " ******");
            LOGGER.fine("**** neededLabels = " + neededLabels + " ******");
            LOGGER.fine("**** prohibitedLabels = " + prohibitedLabels + " ******");
            LockableResourcesManager manager = LockableResourcesManager.get();
            ListBoxModel res = new ListBoxModel();
            Set<ResourceCapability> neededCapabilities = ResourceCapability.splitCapabilities(neededLabels);
            Set<ResourceCapability> prohibitedCapabilities = ResourceCapability.splitCapabilities(prohibitedLabels);
            if(onlyResourceNames) {
                // Add only resource names (sorted)
                TreeSet<LockableResource> resources = new TreeSet<>(getResourcesFromCapabilities(manager.getAllResources(), neededCapabilities, prohibitedCapabilities, null));
                for(LockableResource r : resources) {
                    res.add(r.getName());
                }
            } else {
                // Add all capabilities (sorted)
                TreeSet<ResourceCapability> capabilities = new TreeSet<>(getCompatibleCapabilities(manager.getAllResources(), neededCapabilities, prohibitedCapabilities, null));
                for(ResourceCapability capability : capabilities) {
                    res.add(capability.name);
                }
            }
            return res;
        }
    }
}
