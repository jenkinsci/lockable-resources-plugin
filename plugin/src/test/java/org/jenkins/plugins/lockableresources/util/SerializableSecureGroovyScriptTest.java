package org.jenkins.plugins.lockableresources.util;

import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.junit.Test;

public class SerializableSecureGroovyScriptTest {
    @Test
    public void testRehydrate() {
        SerializableSecureGroovyScript nullCheck = new SerializableSecureGroovyScript(null);
        assertNull("SerializableSecureGroovyScript null check", nullCheck.rehydrate());

        // SecureGroovyScript(@NonNull String script, boolean sandbox, @CheckForNull List<ClasspathEntry> classpath)
        SecureGroovyScript emptyGroovy = new SecureGroovyScript("", false, null);
        SerializableSecureGroovyScript emptyCode = new SerializableSecureGroovyScript(emptyGroovy);
        assertEquals("", emptyCode.rehydrate().getScript(), "SerializableSecureGroovyScript empty check");

        SecureGroovyScript someGroovy = new SecureGroovyScript("echo 'abc'", false, null);
        SerializableSecureGroovyScript someCode = new SerializableSecureGroovyScript(someGroovy);
        assertEquals("echo 'abc'", someCode.rehydrate().getScript(), "SerializableSecureGroovyScript some script check");
    }
}
