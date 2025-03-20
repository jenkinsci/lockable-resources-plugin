package org.jenkins.plugins.lockableresources.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SerializableSecureGroovyScriptTest {

    @Test
    void testRehydrate(JenkinsRule r) throws Exception {
        SerializableSecureGroovyScript nullCheck = new SerializableSecureGroovyScript(null);
        assertNull(nullCheck.rehydrate(), "SerializableSecureGroovyScript null check");

        // SecureGroovyScript(@NonNull String script, boolean sandbox, @CheckForNull
        // List<ClasspathEntry> classpath)
        SecureGroovyScript emptyGroovy = new SecureGroovyScript("", false, null);
        SerializableSecureGroovyScript emptyCode = new SerializableSecureGroovyScript(emptyGroovy);
        assertEquals("", emptyCode.rehydrate().getScript(), "SerializableSecureGroovyScript empty check");

        SecureGroovyScript someGroovy = new SecureGroovyScript("echo 'abc'", false, null);
        SerializableSecureGroovyScript someCode = new SerializableSecureGroovyScript(someGroovy);
        assertEquals(
                "echo 'abc'", someCode.rehydrate().getScript(), "SerializableSecureGroovyScript some script check");
    }
}
