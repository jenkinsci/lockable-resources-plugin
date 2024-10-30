/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, Aki Asikainen. All rights reserved.             *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.model.Descriptor;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;
import org.jenkins.plugins.lockableresources.util.SerializableSecureGroovyScript;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

public class LockableResourcesStruct implements Serializable {

    // Note to developers: if the set of selection criteria variables evolves,
    // do not forget to update LockableResourcesQueueTaskDispatcher.java with
    // class BecauseResourcesLocked method getShortDescription() for user info.
    public List<LockableResource> required;
    public String label;
    public String requiredVar;
    public String requiredNumber;
    public long queuedAt = 0;

    @CheckForNull
    private final SerializableSecureGroovyScript serializableResourceMatchScript;

    @SuppressFBWarnings("SE_TRANSIENT_FIELD_NOT_RESTORED")
    @CheckForNull
    private transient SecureGroovyScript resourceMatchScript;

    private static final long serialVersionUID = 1L;

    public LockableResourcesStruct(RequiredResourcesProperty property, EnvVars env) {
        queuedAt = new Date().getTime();
        required = new ArrayList<>();

        List<String> names = new ArrayList<>();
        for (String name : property.getResources()) {
            String resourceName = env.expand(name);
            if (resourceName == null) {
                continue;
            }
            names.add(resourceName);
        }

        LockableResourcesManager lrm = LockableResourcesManager.get();
        this.required = lrm.fromNames(names, /*create un-existent resources */ true);

        label = env.expand(property.getLabelName());
        if (label == null) label = "";

        resourceMatchScript = property.getResourceMatchScript();
        serializableResourceMatchScript = new SerializableSecureGroovyScript(resourceMatchScript);

        requiredVar = property.getResourceNamesVar();

        requiredNumber = property.getResourceNumber();
        if (requiredNumber != null && requiredNumber.equals("0")) requiredNumber = null;
    }

    /**
     * Light-weight constructor for declaring a resource only.
     *
     * @param resources Resources to be required
     */
    public LockableResourcesStruct(@Nullable List<String> resources) {
        this(resources, null, 0);
    }

    public LockableResourcesStruct(
            @Nullable List<String> resources, @Nullable String label, int quantity, String variable) {
        this(resources, label, quantity);
        requiredVar = variable;
    }

    public LockableResourcesStruct(@Nullable List<String> resources, @Nullable String label, int quantity) {
        queuedAt = new Date().getTime();
        required = new ArrayList<>();
        if (resources != null) {
            /// FIXME do we shall check here if resources.size() >= quantity
            for (String resource : resources) {
                LockableResource r = LockableResourcesManager.get().fromName(resource);
                if (r != null) {
                    this.required.add(r);
                }
            }
        }

        this.label = label;
        if (this.label == null) {
            this.label = "";
        }

        this.requiredNumber = null;
        if (quantity > 0) {
            this.requiredNumber = String.valueOf(quantity);
        }

        // We do not support
        this.serializableResourceMatchScript = null;
        this.resourceMatchScript = null;
    }

    /**
     * Gets a system Groovy script to be executed in order to determine if the {@link
     * LockableResource} matches the condition.
     *
     * @return System Groovy Script if defined
     * @see
     *     LockableResource#scriptMatches(org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript,
     *     java.util.Map)
     * @since 2.1
     */
    @CheckForNull
    public SecureGroovyScript getResourceMatchScript() throws Descriptor.FormException {
        if (resourceMatchScript == null && serializableResourceMatchScript != null) {
            // this is probably high defensive code, because
            resourceMatchScript = serializableResourceMatchScript.rehydrate();
        }
        return resourceMatchScript;
    }

    @CheckForNull
    public String getResourceMatchScriptText() {
        return serializableResourceMatchScript != null ? serializableResourceMatchScript.getScript() : null;
    }

    @Override
    public String toString() {
        String str = "";
        if (this.required != null && !this.required.isEmpty()) {
            str += "Required resources: " + this.required;
        }
        if (this.label != null && !this.label.isEmpty()) {
            str += "Required label: " + this.label;
        }
        if (this.resourceMatchScript != null) {
            str += "Required label script: " + this.resourceMatchScript.getScript();
        }
        if (this.requiredVar != null) {
            str += ", Variable name: " + this.requiredVar;
        }
        if (this.requiredNumber != null) {
            str += ", Number of resources: " + this.requiredNumber;
        }
        return str;
    }

    /** Check if the *resource* is required by this struct / queue */
    @Restricted(NoExternalUse.class)
    public boolean isResourceRequired(final LockableResource resource) {
        if (resource == null) {
            return false;
        }
        return LockableResourcesManager.getResourcesNames(this.required).contains(resource.getName());
    }
}
