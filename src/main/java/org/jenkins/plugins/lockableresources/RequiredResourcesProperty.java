/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.util.FormValidation;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkins.plugins.lockableresources.queue.Utils;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkinsci.plugins.scriptsecurity.scripts.ApprovalContext;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;

public class RequiredResourcesProperty extends JobProperty<Job<?, ?>> {

    private final String resourceNames;
    private final String resourceNamesVar;
    private final String resourceNumber;
    private final String labelName;
    private final @CheckForNull SecureGroovyScript resourceMatchScript;

    @DataBoundConstructor
    public RequiredResourcesProperty(
            String resourceNames,
            String resourceNamesVar,
            String resourceNumber,
            String labelName,
            @CheckForNull SecureGroovyScript resourceMatchScript) {
        super();

        this.resourceNames = Util.fixEmptyAndTrim(resourceNames);
        this.resourceNamesVar = Util.fixEmptyAndTrim(resourceNamesVar);
        this.resourceNumber = Util.fixEmptyAndTrim(resourceNumber);
        if (resourceMatchScript != null) {
            this.resourceMatchScript = resourceMatchScript.configuringWithKeyItem();
            this.labelName = Util.fixEmptyAndTrim(labelName);
        } else if (labelName != null && labelName.startsWith(LockableResource.GROOVY_LABEL_MARKER)) {
            this.resourceMatchScript = new SecureGroovyScript(
                            labelName.substring(LockableResource.GROOVY_LABEL_MARKER.length()), false, null)
                    .configuring(ApprovalContext.create());
            this.labelName = null;
        } else {
            this.resourceMatchScript = null;
            this.labelName = Util.fixEmptyAndTrim(labelName);
        }
    }

    /**
     * @deprecated groovy script was added (since 2.0)
     */
    @Deprecated
    @ExcludeFromJacocoGeneratedReport
    public RequiredResourcesProperty(
            String resourceNames, String resourceNamesVar, String resourceNumber, String labelName) {
        this(resourceNames, resourceNamesVar, resourceNumber, labelName, null);
    }

    private Object readResolve() {
        // SECURITY-368 migration logic
        if (resourceMatchScript == null
                && labelName != null
                && labelName.startsWith(LockableResource.GROOVY_LABEL_MARKER)) {
            return new RequiredResourcesProperty(
                    resourceNames,
                    resourceNamesVar,
                    resourceNumber,
                    null,
                    new SecureGroovyScript(
                                    labelName.substring(LockableResource.GROOVY_LABEL_MARKER.length()), false, null)
                            .configuring(ApprovalContext.create()));
        }

        return this;
    }

    public String[] getResources() {
        String names = Util.fixEmptyAndTrim(resourceNames);
        if (names != null) return names.split("\\s+");
        else return new String[0];
    }

    public String getResourceNames() {
        return resourceNames;
    }

    public String getResourceNamesVar() {
        return resourceNamesVar;
    }

    public String getResourceNumber() {
        return resourceNumber;
    }

    public String getLabelName() {
        return labelName;
    }

    /**
     * Gets a system Groovy script to be executed in order to determine if the {@link
     * LockableResource} matches the condition.
     *
     * @return System Groovy Script if defined
     * @since 2.0
     * @see
     *     LockableResource#scriptMatches(org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript,
     *     java.util.Map)
     */
    @CheckForNull
    public SecureGroovyScript getResourceMatchScript() {
        return resourceMatchScript;
    }

    @Extension
    public static class DescriptorImpl extends JobPropertyDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.RequiredResourcesProperty_displayName();
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return AbstractProject.class.isAssignableFrom(jobType);
        }

        @Override
        public RequiredResourcesProperty newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            if (formData.containsKey("required-lockable-resources")) {
                return (RequiredResourcesProperty)
                        super.newInstance(req, formData.getJSONObject("required-lockable-resources"));
            }
            return null;
        }

        @RequirePOST
        public FormValidation doCheckResourceNames(
                @QueryParameter String value,
                @QueryParameter String labelName,
                @QueryParameter boolean script,
                @AncestorInPath AbstractProject<?, ?> project) {
            // check permission, security first
            checkPermission(project);

            String labelVal = Util.fixEmptyAndTrim(labelName);
            String names = Util.fixEmptyAndTrim(value);

            if (names == null) {
                return FormValidation.ok();
            } else if (labelVal != null || script) {
                return FormValidation.error(Messages.error_labelAndNameOrGroovySpecified());
            } else {
                List<String> wrongNames = new ArrayList<>();
                List<String> varNames = new ArrayList<>();
                List<String> unknownParams = new ArrayList<>();
                for (String name : names.split("\\s+")) {
                    boolean found = LockableResourcesManager.get().resourceExist(name);
                    if (!found) {
                        if (Utils.containsParameter(name)) {
                            List<String> badParams = Utils.checkParameters(name, project);
                            if (!badParams.isEmpty()) {
                                unknownParams.addAll(badParams);
                            }
                            varNames.add(name);
                        } else {
                            wrongNames.add(name);
                        }
                    }
                }
                if (wrongNames.isEmpty() && varNames.isEmpty() && unknownParams.isEmpty()) {
                    return FormValidation.ok();
                } else if (!wrongNames.isEmpty()) {
                    return FormValidation.error(Messages.error_resourceDoesNotExist(wrongNames));
                } else if (!unknownParams.isEmpty()) {
                    return FormValidation.error(Messages.error_parameterDoesNotExist(unknownParams));
                } else {
                    return FormValidation.warning(Messages.warn_resourceNotValidated(varNames));
                }
            }
        }

        @RequirePOST
        public FormValidation doCheckLabelName(
                @QueryParameter String value,
                @QueryParameter String resourceNames,
                @QueryParameter boolean script,
                @AncestorInPath AbstractProject<?, ?> project) {
            // check permission, security first
            checkPermission(project);

            String label = Util.fixEmptyAndTrim(value);
            String names = Util.fixEmptyAndTrim(resourceNames);

            if (label == null) {
                return FormValidation.ok();
            } else if (names != null || script) {
                return FormValidation.error(Messages.error_labelAndNameOrGroovySpecified());
            } else {
                if (LockableResourcesManager.get().isValidLabel(label)) {
                    return FormValidation.ok();
                } else if (Utils.containsParameter(label)) {
                    List<String> badParams = Utils.checkParameters(label, project);
                    if (!badParams.isEmpty()) {
                        return FormValidation.error(Messages.error_parameterDoesNotExist(badParams));
                    }
                    return FormValidation.warning(Messages.warn_labelNotValidated(label));
                } else {
                    return FormValidation.error(Messages.error_labelDoesNotExist(label));
                }
            }
        }

        @RequirePOST
        public FormValidation doCheckResourceNumber(
                @QueryParameter String value,
                @QueryParameter String resourceNames,
                @QueryParameter String labelName,
                @QueryParameter String resourceMatchScript,
                @AncestorInPath AbstractProject<?, ?> project) {
            // check permission, security first
            checkPermission(project);

            String number = Util.fixEmptyAndTrim(value);
            String names = Util.fixEmptyAndTrim(resourceNames);
            String label = Util.fixEmptyAndTrim(labelName);
            String script = Util.fixEmptyAndTrim(resourceMatchScript);

            if (number == null || number.equals("0")) {
                return FormValidation.ok();
            }

            int numAsInt = 0;
            try {
                numAsInt = Integer.parseInt(number);
            } catch (NumberFormatException e) {
                if (Utils.isParameter(number)) {
                    List<String> badParams = Utils.checkParameters(number, project);
                    if (!badParams.isEmpty()) {
                        return FormValidation.error(Messages.error_parameterDoesNotExist(badParams));
                    }
                    return FormValidation.warning(Messages.warn_valueNotValidated(number));
                }
                return FormValidation.error(Messages.error_couldNotParseToInt());
            }

            int numResources = 0;
            if (names != null) {
                numResources = names.split("\\s+").length;
            } else if (label != null) {
                numResources = LockableResourcesManager.get()
                        .getResourcesWithLabel(label, null)
                        .size();
            } else if (script != null) {
                numResources = Integer.MAX_VALUE;
            }

            if (numAsInt > numResources) {
                return FormValidation.error(String.format(
                        Messages.error_givenAmountIsGreaterThanResourcesAmount(), numAsInt, numResources));
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public AutoCompletionCandidates doAutoCompleteLabelName(
                @QueryParameter String value, @AncestorInPath Item item) {
            // check permission, security first
            checkPermission(item);

            AutoCompletionCandidates c = new AutoCompletionCandidates();

            value = Util.fixEmptyAndTrim(value);

            if (value == null) {
                return c;
            }
            for (String l : LockableResourcesManager.get().getAllLabels()) {
                if (l.startsWith(value)) {
                    c.add(l);
                }
            }

            return c;
        }

        @RequirePOST
        public static AutoCompletionCandidates doAutoCompleteResourceNames(
                @QueryParameter String value, @AncestorInPath Item item) {
            // check permission, security first
            checkPermission(item);

            AutoCompletionCandidates c = new AutoCompletionCandidates();

            value = Util.fixEmptyAndTrim(value);

            if (value == null) {
                return c;
            }
            List<String> allNames = LockableResourcesManager.get().getAllResourcesNames();
            for (String name : allNames) {
                if (name.startsWith(value)) {
                    c.add(name);
                }
            }

            return c;
        }

        private static void checkPermission(Item item) {
            if (item != null) {
                item.checkPermission(Item.CONFIGURE);
            } else {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            }
        }
    }
}
