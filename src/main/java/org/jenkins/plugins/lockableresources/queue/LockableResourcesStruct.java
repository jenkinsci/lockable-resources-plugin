/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
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
import org.jenkinsci.plugins.scriptsecurity.scripts.ApprovalContext;
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

    /** Optional sandboxed script ordering label candidates, see {@link #getSortByScript()}. */
    @CheckForNull
    private SerializableSecureGroovyScript serializableSortByScript;

    @SuppressFBWarnings("SE_TRANSIENT_FIELD_NOT_RESTORED")
    @CheckForNull
    private transient SecureGroovyScript sortByScript;

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

        requiredNumber = env.expand(property.getResourceNumber());
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

    /**
     * Constructor for a lock request whose label candidates are ordered by a script.
     *
     * @param sortByScript Groovy expression evaluated per candidate; lower values are preferred. It is
     *     always run in the script sandbox.
     */
    public LockableResourcesStruct(
            @Nullable List<String> resources,
            @Nullable String label,
            int quantity,
            @Nullable String variable,
            @CheckForNull String sortByScript) {
        this(resources, label, quantity);
        requiredVar = variable;
        if (sortByScript != null && !sortByScript.isBlank()) {
            try {
                this.sortByScript = createSortByScript(sortByScript);
            } catch (Descriptor.FormException e) {
                throw new IllegalArgumentException("Invalid sortBy script: " + e.getMessage(), e);
            }
            this.serializableSortByScript = new SerializableSecureGroovyScript(this.sortByScript);
        }
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

    /**
     * Gets the script used to order label candidates. Lower results are preferred.
     *
     * @return sandboxed script, or {@code null} if no ordering was requested
     * @see LockableResource#evaluateSortScript(SecureGroovyScript, java.util.Map)
     */
    @CheckForNull
    @Restricted(NoExternalUse.class)
    public SecureGroovyScript getSortByScript() throws Descriptor.FormException {
        String text = getSortByScriptText();
        if (sortByScript == null && text != null) {
            // rebuilt from the text only, so the sandbox can never be turned off
            sortByScript = createSortByScript(text);
        }
        return sortByScript;
    }

    @CheckForNull
    public String getSortByScriptText() {
        return serializableSortByScript != null ? serializableSortByScript.getScript() : null;
    }

    private static SecureGroovyScript createSortByScript(String script) throws Descriptor.FormException {
        return new SecureGroovyScript(script, true, null).configuring(ApprovalContext.create());
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
        if (this.serializableSortByScript != null) {
            str += ", Sort by: " + this.serializableSortByScript.getScript();
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
