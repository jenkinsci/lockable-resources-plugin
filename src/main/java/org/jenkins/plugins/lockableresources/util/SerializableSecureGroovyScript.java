/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.util;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkinsci.plugins.scriptsecurity.scripts.ClasspathEntry;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Wrapper for a {@link SecureGroovyScript}.
 *
 * @author Oleg Nenashev
 */
@Restricted(NoExternalUse.class)
public class SerializableSecureGroovyScript implements Serializable {

    private static final long serialVersionUID = 1L;

    @CheckForNull
    private final String script;

    private final boolean sandbox;
    /** {@code null} if and only if the {@link #script is null}. */
    @Nullable
    private final ArrayList<SerializableClassPathEntry> classPathEntries;

    private static final Logger LOGGER = Logger.getLogger(SerializableSecureGroovyScript.class.getName());

    public SerializableSecureGroovyScript(@CheckForNull SecureGroovyScript secureScript) {
        if (secureScript == null) {
            script = null;
            sandbox = false;
            classPathEntries = null;
        } else {
            this.script = secureScript.getScript();
            this.sandbox = secureScript.isSandbox();

            List<ClasspathEntry> classpath = secureScript.getClasspath();
            classPathEntries = new ArrayList<>(classpath.size());
            for (ClasspathEntry e : classpath) {
                classPathEntries.add(new SerializableClassPathEntry(e));
            }
        }
    }

    @CheckForNull
    public String getScript() {
        return script;
    }

    @CheckForNull
    public SecureGroovyScript rehydrate() throws Descriptor.FormException {
        if (script == null) {
            return null;
        }

        ArrayList<ClasspathEntry> p = new ArrayList<>(classPathEntries.size());
        for (SerializableClassPathEntry e : classPathEntries) {
            ClasspathEntry entry = e.rehydrate();
            if (entry != null) {
                p.add(entry);
            }
        }

        return new SecureGroovyScript(script, sandbox, p);
    }

    private static class SerializableClassPathEntry implements Serializable {

        private static final long serialVersionUID = 1L;

        private final String url;

        private SerializableClassPathEntry(@NonNull ClasspathEntry entry) {
            this.url = entry.getPath();
        }

        @CheckForNull
        private ClasspathEntry rehydrate() {
            try {
                ClasspathEntry entry = new ClasspathEntry(url);
                if (ScriptApproval.get().checking(entry).kind.equals(FormValidation.Kind.OK)) {
                    return entry;
                } else {
                    return null;
                }
            } catch (MalformedURLException ex) {
                // Unrealistic
                LOGGER.log(Level.SEVERE, "Failed to rehydrate the URL " + url + ". It will be skipped", ex);
                return null;
            }
        }
    }
}
