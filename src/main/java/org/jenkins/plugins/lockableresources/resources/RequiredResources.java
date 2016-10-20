/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, Aki Asikainen. All rights reserved.             *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.resources;

import hudson.EnvVars;
import hudson.Extension;
import hudson.ExtensionPoint;
import hudson.Util;
import hudson.matrix.MatrixConfiguration;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.util.FormValidation;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import javax.annotation.Nullable;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.jobProperty.RequiredResourcesProperty;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;

public class RequiredResources extends AbstractDescribableImpl<RequiredResources> implements Serializable, ExtensionPoint {
    private static final long serialVersionUID = 1L;
    @Exported
    protected String resources; // List of resources names (space or comma separated)
    @Exported
    protected String labels; // Single label or list of capabilities (space or comma separated) ('resources' must be null/empty)
    @Exported
    protected Integer quantity; // Nb of resources required (only with 'resources' == null/empty)
    @Exported
    protected String variableName; // Name of variable that will store locked resources after selection

    @DataBoundConstructor
    public RequiredResources(String resources, String labels, int quantity, String variableName) {
        this.resources = Util.fixNull(resources);
        this.labels = Util.fixNull(labels);
        this.quantity = quantity;
        this.variableName = Util.fixNull(variableName);
    }

    /**
     * Only for jelly file compatibility
     * Please use {@link #getResources(EnvVars)}
     *
     * @return
     *
     * @deprecated
     */
    @Exported
    @Deprecated
    public String getResources() {
        return resources;
    }

    public String getExpandedResources(@Nullable EnvVars env) {
        if(env == null) {
            return resources;
        }
        return env.expand(resources);
    }

    @DataBoundSetter
    public void setResources(String resources) {
        this.resources = resources;
    }

    /**
     * Only for jelly file compatibility
     * Please use {@link #getLabels(EnvVars)}
     *
     * @return
     *
     * @deprecated
     */
    @Exported
    @Deprecated
    public String getLabels() {
        return labels;
    }

    public String getExpandedLabels(@Nullable EnvVars env) {
        if(env == null) {
            return labels;
        }
        return env.expand(labels);
    }

    @DataBoundSetter
    public void setLabels(String labels) {
        this.labels = labels;
    }

    @Exported
    public String getVariableName() {
        return variableName;
    }

    @DataBoundSetter
    public void setVariableName(String variableName) {
        this.variableName = variableName;
    }

    @Exported
    public Integer getQuantity() {
        return quantity;
    }

    @DataBoundSetter
    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Set<LockableResource> getResourcesList(@Nullable EnvVars env) {
        return LockableResourcesManager.get().getResourcesFromNames(getResourceNamesList(env));
    }

    public Set<String> getResourceNamesList(@Nullable EnvVars env) {
        return Utils.splitLabels(getExpandedResources(env));
    }

    public Set<ResourceCapability> getCapabilitiesList(@Nullable EnvVars env) {
        return ResourceCapability.splitCapabilities(getExpandedLabels(env));
    }

    @Override
    public String toString() {
        // An exact format is currently needed for tests
        if((Util.fixEmpty(resources) != null) && (Util.fixEmpty(labels) == null) && (quantity == 0)) {
            return resources;
        }
        if((Util.fixEmpty(resources) == null) && (Util.fixEmpty(labels) != null) && (quantity == 0)) {
            return labels;
        }

        String lbl = "";
        if(Util.fixEmpty(resources) != null) {
            lbl += "Resource: " + resources;
        }
        if(Util.fixEmpty(labels) != null) {
            if(!lbl.isEmpty()) {
                lbl += ", ";
            }
            lbl += "Label: " + labels;
        }
        if((quantity != null) && (quantity > 0)) {
            if(!lbl.isEmpty()) {
                lbl += ", ";
            }
            lbl += "Quantity: " + quantity;
        }
        if(Util.fixEmpty(variableName) != null) {
            if(!lbl.isEmpty()) {
                lbl += ", ";
            }
            lbl += "Variable: " + variableName;
        }
        return lbl;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<RequiredResources> {
        @Override
        public String getDisplayName() {
            return "Required Lockable Resources";
        }

        public static FormValidation doCheckResources(@QueryParameter String value) {
            String names = Util.fixEmptyAndTrim(value);
            if(names == null) {
                return FormValidation.ok();
            } else {
                LockableResourcesManager manager = LockableResourcesManager.get();
                Set<String> wrongNames = manager.getInvalidResourceNames(Utils.splitLabels(value));
                if(wrongNames.isEmpty()) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error("The following resources do not exist: " + wrongNames);
                }
            }
        }

        public static FormValidation doCheckLabels(
                @QueryParameter String value,
                @QueryParameter String resources) {
            String label = Util.fixEmptyAndTrim(value);
            String names = Util.fixEmptyAndTrim(resources);
            if(label == null) {
                return FormValidation.ok();
            } else if(names != null) {
                return FormValidation.error("Only label or resources can be defined, not both.");
            } else {
                Set<String> labels = Utils.splitLabels(label);
                for(String l : labels) {
                    if(!LockableResourcesManager.get().isValidLabel(l, true)) {
                        return FormValidation.error("The label does not exist: " + l);
                    }
                }
                return FormValidation.ok();
            }
        }

        public static FormValidation doCheckQuantity(
                @QueryParameter String value,
                @QueryParameter String resources) {
            String number = Util.fixEmptyAndTrim(value);
            String names = Util.fixEmptyAndTrim(resources);
            if(number == null || number.equals("0")) {
                return FormValidation.ok();
            }
            int numAsInt;
            try {
                numAsInt = Integer.parseInt(number);
            } catch(NumberFormatException e) {
                return FormValidation.error("Invalid integer value.");
            }
            if(names != null) {
                int numResources = Utils.splitLabels(names).size();
                if(numResources < numAsInt) {
                    return FormValidation.error("Given amount " + numAsInt + " is greater than amount of resources: " + numResources + ".");
                }
            }
            return FormValidation.ok();
        }

        public static AutoCompletionCandidates doAutoCompleteLabels(
                @QueryParameter String value) {
            AutoCompletionCandidates c = new AutoCompletionCandidates();
            value = Util.fixEmptyAndTrim(value);
            if(value != null) {
                LockableResourcesManager manager = LockableResourcesManager.get();
                for(String l : manager.getAllLabels(true)) {
                    if(l.startsWith(value)) {
                        c.add(l);
                    }
                }
            }
            return c;
        }

        public static AutoCompletionCandidates doAutoCompleteResources(
                @QueryParameter String value) {
            AutoCompletionCandidates c = new AutoCompletionCandidates();
            value = Util.fixEmptyAndTrim(value);
            if(value != null) {
                LockableResourcesManager manager = LockableResourcesManager.get();
                for(LockableResource r : manager.getAllResources()) {
                    if(r.getName().startsWith(value)) {
                        c.add(r.getName());
                    }
                }
            }
            return c;
        }
    }

    public static Collection<RequiredResources> getRequiredResources(Job<?, ?> project) {
        EnvVars env = new EnvVars();

        if(project instanceof MatrixConfiguration) {
            env.putAll(((MatrixConfiguration) project).getCombination());

            project = (Job<?, ?>) project.getParent();
        }

        RequiredResourcesProperty property = project.getProperty(RequiredResourcesProperty.class);

        if(property == null) {
            return Collections.emptyList();
        }
        return property.getRequiredResourcesList();
    }
}
