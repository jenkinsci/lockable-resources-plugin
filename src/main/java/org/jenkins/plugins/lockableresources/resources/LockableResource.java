/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.resources;

import com.infradna.tool.bridge_method_injector.WithBridgeMethods;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractBuild;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Queue;
import hudson.model.Queue.Item;
import hudson.model.Queue.Task;
import hudson.model.Run;
import hudson.model.User;
import hudson.tasks.Mailer.UserProperty;
import hudson.util.XStream2;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import jenkins.model.Jenkins;
import org.codehaus.groovy.control.CompilationFailedException;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.step.LockStep;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

@ExportedBean(defaultVisibility = 999)
public class LockableResource extends AbstractDescribableImpl<LockableResource> implements Serializable, Comparable<LockableResource> {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(LockableResource.class.getName());
    public static final int NOT_QUEUED = 0;
    /**
     * Groovy scripts are limited to labels of defined resources
     * They can not be use for other structure (in particular RequiredResources)
     */
    public static final String GROOVY_LABEL_MARKER = "groovy:";
    private static final int QUEUE_TIMEOUT = 60;
    @Exported
    protected final String name;
    @Exported
    protected String description = "";
    @Exported
    protected String labels = "";
    @Exported
    protected String reservedBy = null;
    private long queueItemId = NOT_QUEUED;
    private String queueItemProject = null;
    private transient Run<?, ?> build = null;
    // Needed to make the state non-transient
    private String buildExternalizableId = null;
    private long queuingStarted = 0;
    /**
     * Only used when this lockable resource is tried to be locked by {@link LockStep},
     * otherwise (freestyle builds) regular Jenkins queue is used.
     */
    private List<StepContext> queuedContexts = new ArrayList<>();

    @DataBoundConstructor
    public LockableResource(String name) {
        this.name = name;
    }

    public LockableResource(String name, String labels) {
        this.name = name;
        this.labels = labels;
    }

    @Exported
    public String getName() {
        return name;
    }

    @DataBoundSetter
    public void setDescription(String description) {
        this.description = description;
    }

    @Exported
    public String getDescription() {
        return description;
    }

    @DataBoundSetter
    public void setLabels(String labels) {
        this.labels = labels;
    }

    @Exported
    public String getLabels() {
        if(labels.startsWith(GROOVY_LABEL_MARKER)) {
            return "";
        }
        return labels;
    }

    @DataBoundSetter
    public void setReservedBy(String userName) {
        this.reservedBy = Util.fixEmptyAndTrim(userName);
    }

    @Exported
    public String getReservedBy() {
        return reservedBy;
    }

    public void unReserve() {
        this.reservedBy = null;
    }

    public Set<ResourceCapability> getCapabilities() {
        if(labels.startsWith(GROOVY_LABEL_MARKER)) {
            // Special case: the whole label is a groovy script
            return new TreeSet<>();
        }
        return ResourceCapability.splitCapabilities(labels);
    }

    public ResourceCapability getMyselfAsCapability() {
        return new ResourceCapability(name);
    }

    public boolean hasCapabilities(Collection<ResourceCapability> capabilities, @Nullable EnvVars env) {
        return hasCapabilities(capabilities, null, env);
    }

    public boolean hasCapabilities(@Nullable Collection<ResourceCapability> neededCapabilities,
            @Nullable Collection<ResourceCapability> prohibitedCapabilities, @Nullable EnvVars env) {
        if(neededCapabilities != null) {
            for(Iterator<ResourceCapability> it = neededCapabilities.iterator(); it.hasNext();) {
                ResourceCapability r = it.next();
                if(r.getName().startsWith(GROOVY_LABEL_MARKER)) {
                    // Test the groovy returned value
                    if((env != null) && !expressionMatches(r.getName(), env)) {
                        return false;
                    }
                    // Remove the current element from the iterator and the list.
                    it.remove();
                }
            }
        }
        if(prohibitedCapabilities != null) {
            for(Iterator<ResourceCapability> it = prohibitedCapabilities.iterator(); it.hasNext();) {
                ResourceCapability r = it.next();
                if(r.getName().startsWith(GROOVY_LABEL_MARKER)) {
                    // Test the groovy returned value
                    if((env != null) && expressionMatches(r.getName(), env)) {
                        return false;
                    }
                    // Remove the current element from the iterator and the list.
                    it.remove();
                }
            }
        }
        Set<ResourceCapability> capabilities = getCapabilities();
        capabilities.add(getMyselfAsCapability());
        return ResourceCapability.hasAllCapabilities(capabilities, neededCapabilities) && ResourceCapability.hasNoneOfCapabilities(capabilities, prohibitedCapabilities);
    }

    public boolean isValidLabel(String candidate, @Nonnull EnvVars env) {
        return candidate.startsWith(GROOVY_LABEL_MARKER) ? expressionMatches(candidate, env) : labelsContain(candidate);
    }

    private boolean expressionMatches(String expression, @Nonnull EnvVars env) {
        Binding binding = new Binding(env);
        binding.setVariable("resourceName", name);
        binding.setVariable("resourceDescription", description);
        binding.setVariable("resourceLabels", labels);
        String expressionToEvaluate = expression.replace(GROOVY_LABEL_MARKER, "");
        GroovyShell shell = new GroovyShell(binding);
        try {
            Object result = shell.evaluate(expressionToEvaluate);
            if(LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Checked resource " + name + " for " + expression + " with " + binding + " -> " + result);
            }
            return (Boolean) result;
        } catch(CompilationFailedException e) {
            LOGGER.log(Level.SEVERE, "Cannot get boolean result out of groovy expression '"
                    + expressionToEvaluate + "' on (" + binding + ")", e);
            return false;
        }
    }

    private boolean labelsContain(String candidate) {
        return Utils.splitLabels(labels).contains(candidate);
    }

    public boolean isReserved() {
        return reservedBy != null;
    }

    public String getReservedByEmail() {
        if(reservedBy != null) {
            Jenkins jenkins = Jenkins.getInstance();
            if(jenkins == null) {
                throw new IllegalStateException("Jenkins instance has not been started or was already shut down.");
            }
            User user = jenkins.getUser(reservedBy);
            if(user != null) {
                UserProperty email = user.getProperty(UserProperty.class);
                if(email != null) {
                    return email.getAddress();
                }
            }
        }
        return null;
    }

    public boolean isQueued() {
        this.validateQueuingTimeout();
        return queueItemId != NOT_QUEUED;
    }

    // returns True if queued by any other task than the given one
    public boolean isQueued(long taskId) {
        this.validateQueuingTimeout();
        return queueItemId != NOT_QUEUED && queueItemId != taskId;
    }

    public boolean isQueuedByTask(long taskId) {
        this.validateQueuingTimeout();
        return queueItemId == taskId;
    }

    public void unqueue() {
        queueItemId = NOT_QUEUED;
        queueItemProject = null;
        queuingStarted = 0;
    }

    public boolean isLocked() {
        return getBuild() != null;
    }

    public boolean isFree() {
        return (!isLocked() && !isReserved() && !isQueued());
    }

    public boolean canLock() {
        return (!isLocked() && !isReserved());
    }

    /**
     * Resolve the lock cause for this resource. It can be reserved or locked.
     *
     * @return the lock cause or null if not locked
     */
    @CheckForNull
    public String getLockCause() {
        if(isReserved()) {
            return String.format("[%s] is reserved by %s", name, reservedBy);
        }
        if(isLocked()) {
            return String.format("[%s] is locked by %s", name, buildExternalizableId);
        }
        return null;
    }

    @WithBridgeMethods(value = AbstractBuild.class, adapterMethod = "getAbstractBuild")
    public Run<?, ?> getBuild() {
        if(build == null && buildExternalizableId != null) {
            build = Run.fromExternalizableId(buildExternalizableId);
        }
        return build;
    }

    @Exported
    public String getBuildName() {
        if(getBuild() != null) {
            return getBuild().getFullDisplayName();
        } else {
            return null;
        }
    }

    public void setBuild(Run<?, ?> lockedBy) {
        this.build = lockedBy;
        if(lockedBy != null) {
            this.buildExternalizableId = lockedBy.getExternalizableId();
        } else {
            this.buildExternalizableId = null;
        }
    }

    public Task getTask() {
        Item item = Queue.getInstance().getItem(queueItemId);
        if(item != null) {
            return item.task;
        } else {
            return null;
        }
    }

    public long getQueueItemId() {
        this.validateQueuingTimeout();
        return queueItemId;
    }

    public String getQueueItemProject() {
        this.validateQueuingTimeout();
        return this.queueItemProject;
    }

    public void setQueued(long queueItemId) {
        this.queueItemId = queueItemId;
        this.queuingStarted = System.currentTimeMillis() / 1000;
    }

    public void setQueued(long queueItemId, String queueProjectName) {
        this.setQueued(queueItemId);
        this.queueItemProject = queueProjectName;
    }

    private void validateQueuingTimeout() {
        if(queuingStarted > 0) {
            long now = System.currentTimeMillis() / 1000;
            if(now - queuingStarted > QUEUE_TIMEOUT) {
                unqueue();
            }
        }
    }

    public void queueAdd(StepContext context) {
        queuedContexts.add(context);
    }

    public void reset() {
        this.unReserve();
        this.unqueue();
        this.setBuild(null);
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if(this == obj) {
            return true;
        }
        if(obj == null) {
            return false;
        }
        if(getClass() != obj.getClass()) {
            return false;
        }
        LockableResource other = (LockableResource) obj;
        if(name == null) {
            if(other.name != null) {
                return false;
            }
        } else if(!name.equals(other.name)) {
            return false;
        }
        return true;
    }

    @Override
    public int compareTo(LockableResource o) {
        if(name == null) {
            if(o.name == null) {
                return 0;
            } else {
                return -1;
            }
        }
        return name.compareTo(o.name);
    }

    /**
     * Magically called when imported from XML file
     * Manage backward compatibility
     * @return 
     */
    private Object readResolve() {
        if(queuedContexts == null) { // this field was added after the initial version if this class
            queuedContexts = new ArrayList<>();
        }
        return this;
    }
    
    @Extension
    public static class DescriptorImpl extends Descriptor<LockableResource> {
        private static final XStream2 XSTREAM2 = new XStream2();

        /**
         * Add backward compatibility
         */
        @Initializer(before = InitMilestone.PLUGINS_STARTED)
        public static void addAliases() {
            XSTREAM2.addCompatibilityAlias("org.jenkins.plugins.lockableresources.LockableResource", LockableResource.class);
        }

        @Override
        public String getDisplayName() {
            return "Resource";
        }
    }
}
