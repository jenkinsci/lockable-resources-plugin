package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jenkins.plugins.lockableresources.remote.RemoteLockSession;
import org.jenkins.plugins.lockableresources.remote.RemoteResolver;
import org.junit.jupiter.api.Test;

class LockStepExecutionEnvVarsTest {

    @Test
    void buildLockEnvVarsIncludesIndexedLabels() {
        LockableResourceProperty property = new LockableResourceProperty();
        property.setName("LABELS");
        property.setValue("custom property");
        LinkedHashMap<String, List<LockableResourceProperty>> lockedResources = new LinkedHashMap<>();
        lockedResources.put("plc-a", List.of(property));
        lockedResources.put("plc-b", List.of());
        lockedResources.put("plc-c", List.of());

        Map<String, String> labels = Map.of("plc-a", "hw fast", "plc-b", "hw");
        Map<String, String> env = LockStepExecution.buildLockEnvVars("PLC", lockedResources, labels);

        assertEquals("hw fast", env.get("PLC0_LABELS"));
        assertEquals("hw", env.get("PLC1_LABELS"));
        assertEquals("", env.get("PLC2_LABELS"));
        assertEquals("hw fast", env.get("PLC_LABELS"));
        assertNull(LockStepExecution.buildLockEnvVars(null, lockedResources, labels));
        assertNull(LockStepExecution.buildLockEnvVars("", lockedResources, labels));
    }

    @Test
    void remoteLockEnvVarsIncludesResourceLabels() {
        LockableResource first = new LockableResource("plc-a");
        first.setLabelsFromString("hw fast");
        LockableResource second = new LockableResource("plc-b");

        Map<String, String> env = RemoteResolver.remoteLockEnvVars("PLC", List.of(first, second));

        assertEquals("hw fast", env.get("PLC0_LABELS"));
        assertEquals("", env.get("PLC1_LABELS"));
        assertEquals("hw fast", env.get("PLC_LABELS"));
    }

    @Test
    void buildLockEnvVarsLabelAliasUsesFirstResourceEvenWhenUnlabeled() {
        LinkedHashMap<String, List<LockableResourceProperty>> lockedResources = new LinkedHashMap<>();
        lockedResources.put("plc-a", List.of());
        lockedResources.put("plc-b", List.of());

        Map<String, String> env = LockStepExecution.buildLockEnvVars("PLC", lockedResources, Map.of("plc-b", "hw"));

        assertEquals("", env.get("PLC_LABELS"));
        assertEquals("hw", env.get("PLC1_LABELS"));
    }

    @Test
    void buildLockEnvVarsIncludesIndexedNamesAndProperties() {
        LockableResourceProperty p0 = new LockableResourceProperty();
        p0.setName("ip");
        p0.setValue("10.0.0.11");

        LockableResourceProperty p1 = new LockableResourceProperty();
        p1.setName("ip");
        p1.setValue("10.0.0.12");

        LinkedHashMap<String, List<LockableResourceProperty>> lockedResources = new LinkedHashMap<>();
        lockedResources.put("plc-a", List.of(p0));
        lockedResources.put("plc-b", List.of(p1));

        Map<String, String> env = LockStepExecution.buildLockEnvVars("PLC", lockedResources);

        assertEquals("plc-a,plc-b", env.get("PLC"));
        assertEquals("plc-a", env.get("PLC0"));
        assertEquals("10.0.0.11", env.get("PLC0_ip"));
        // JENKINS-75943: the un-indexed alias must resolve to the FIRST resource's property value
        assertEquals("10.0.0.11", env.get("PLC_ip"));
        assertEquals("plc-b", env.get("PLC1"));
        assertEquals("10.0.0.12", env.get("PLC1_ip"));
    }

    @Test
    void buildLockEnvVarsExposesFirstResourcePropertiesWithoutIndex() {
        LockableResourceProperty ip = new LockableResourceProperty();
        ip.setName("ip");
        ip.setValue("10.0.0.11");

        LockableResourceProperty port = new LockableResourceProperty();
        port.setName("port");
        port.setValue("8080");

        LinkedHashMap<String, List<LockableResourceProperty>> lockedResources = new LinkedHashMap<>();
        lockedResources.put("plc-a", List.of(ip, port));

        Map<String, String> env = LockStepExecution.buildLockEnvVars("PLC", lockedResources);

        // existing indexed vars are unchanged
        assertEquals("plc-a", env.get("PLC"));
        assertEquals("plc-a", env.get("PLC0"));
        assertEquals("10.0.0.11", env.get("PLC0_ip"));
        assertEquals("8080", env.get("PLC0_port"));

        // JENKINS-75943: same property values are additionally exposed without the numeric index
        assertEquals("10.0.0.11", env.get("PLC_ip"));
        assertEquals("8080", env.get("PLC_port"));
    }

    @Test
    void buildLockEnvVarsReturnsNullWhenVariableMissing() {
        LinkedHashMap<String, List<LockableResourceProperty>> lockedResources = new LinkedHashMap<>();
        lockedResources.put("plc-a", List.of());

        assertNull(LockStepExecution.buildLockEnvVars(null, lockedResources));
        assertNull(LockStepExecution.buildLockEnvVars("", lockedResources));
    }

    @Test
    @SuppressWarnings("unchecked")
    void remoteMetadataAddsServerIdAndLockId() throws Exception {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("PLC", "plc-a,plc-b");
        base.put("PLC0", "plc-a");
        base.put("PLC1", "plc-b");

        RemoteLockSession session = new RemoteLockSession();
        Field serverId = RemoteLockSession.class.getDeclaredField("serverId");
        serverId.setAccessible(true);
        serverId.set(session, "server-a");

        Method m = LockStepExecution.class.getDeclaredMethod(
                "withRemoteMetadata", Map.class, String.class, RemoteLockSession.class, String.class);
        m.setAccessible(true);

        Map<String, String> merged = (Map<String, String>) m.invoke(null, base, "PLC", session, "lock-1");

        assertEquals("plc-a,plc-b", merged.get("PLC"));
        assertEquals("server-a", merged.get("PLC_SERVER_ID"));
        assertEquals("lock-1", merged.get("PLC_LOCK_ID"));
        assertTrue(merged.keySet().containsAll(base.keySet()));
    }
}
