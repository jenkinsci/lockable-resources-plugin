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
import org.junit.jupiter.api.Test;

class LockStepExecutionEnvVarsTest {

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
        assertEquals("plc-b", env.get("PLC1"));
        assertEquals("10.0.0.12", env.get("PLC1_ip"));
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
