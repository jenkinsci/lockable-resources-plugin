package org.jenkins.plugins.lockableresources.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Field;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class LockedResourcesBuildActionTest {

    @Test
    void writeReplaceHandlesNullDeserializedFields() throws Exception {
        LockedResourcesBuildAction action = new LockedResourcesBuildAction();

        // Simulate old/corrupted deserialized state where list fields are null.
        Field logsField = LockedResourcesBuildAction.class.getDeclaredField("logs");
        logsField.setAccessible(true);
        logsField.set(action, null);

        Field resourcesInUseField = LockedResourcesBuildAction.class.getDeclaredField("resourcesInUse");
        resourcesInUseField.setAccessible(true);
        resourcesInUseField.set(action, null);

        Object replacement = action.writeReplace();
        assertNotNull(replacement);

        LockedResourcesBuildAction replaced = assertInstanceOf(LockedResourcesBuildAction.class, replacement);
        assertEquals(Collections.emptyList(), replaced.getReadOnlyLogs());
        assertEquals(Collections.emptyList(), replaced.getReadOnlyResourcesInUse());

        // Verify mutation paths are also resilient after null-list recovery.
        action.addLog("resource-a", "lock", "acquired");
        action.addUsedResources(Collections.singletonList("resource-a"));
        assertEquals(1, action.getReadOnlyLogs().size());
        assertEquals(Collections.singletonList("resource-a"), action.getReadOnlyResourcesInUse());
    }
}
