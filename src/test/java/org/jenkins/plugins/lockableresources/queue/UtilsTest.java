package org.jenkins.plugins.lockableresources.queue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UtilsTest {

    @Test
    void containsVariable() {
        assertTrue(Utils.containsVariable("${MY_PARAM}"));
        assertTrue(Utils.containsVariable("prefix-${MY_PARAM}"));
        assertTrue(Utils.containsVariable("${A}-${B}"));
        assertTrue(Utils.containsVariable("resource-${BRANCH_NAME}-lock"));

        assertFalse(Utils.containsVariable(null));
        assertFalse(Utils.containsVariable(""));
        assertFalse(Utils.containsVariable("plain-resource"));
        assertFalse(Utils.containsVariable("$NOT_A_VAR"));
        assertFalse(Utils.containsVariable("${}")); // empty var name
    }
}
