package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LockableResourceTest {

    private final LockableResource instance = new LockableResource("r1");

    // Not sure how useful this is...
    @Test
    void testGetters() {
        assertEquals("r1", instance.getName());
        assertEquals("", instance.getDescription());
        assertEquals("", instance.getLabelsAsString());
        assertEquals("", instance.getNote());
        assertNull(instance.getReservedBy());
        assertNull(instance.getReservedTimestamp());
        assertFalse(instance.isReserved());
        assertFalse(instance.isQueued());
        assertFalse(instance.isQueued(0));
        assertFalse(instance.isQueuedByTask(1));
        assertFalse(instance.isLocked());
        assertNull(instance.getBuild());
        assertEquals(0, instance.getQueueItemId());
        assertNull(instance.getQueueItemProject());
    }

    @Test
    void testNote() {
        final LockableResource resource = new LockableResource("Name 1");

        assertEquals("", resource.getNote());

        resource.setNote("Note 1");
        assertEquals("Note 1", resource.getNote());

        resource.setNote("Note B");
        assertEquals("Note B", resource.getNote());

        resource.setNote("");
        assertEquals("", resource.getNote());
    }

    @Test
    void testUnqueue() {
        instance.unqueue();
    }

    @Test
    void testSetBuild() {
        instance.setBuild(null);
    }

    @Test
    void testSetReservedBy() {
        instance.setReservedBy("");
    }

    @Test
    void testReservedTimestamp() {
        instance.setReservedTimestamp(null);
        assertNull(instance.getReservedTimestamp());

        final Date date = new Date();
        instance.setReservedTimestamp(date);
        assertEquals(date, instance.getReservedTimestamp());
    }

    @Test
    void testReserve() {
        instance.reserve("testUser1");
        assertEquals("testUser1", instance.getReservedBy());
        assertNotNull(instance.getReservedTimestamp());
    }

    @Test
    void testUnReserve() {
        instance.unReserve();
        assertNull(instance.getReservedBy());
        assertNull(instance.getReservedTimestamp());
    }

    @Test
    void testReset() {
        instance.reset();
    }

    @Test
    void testToString() {
        assertEquals("r1", instance.toString());
    }

    @Test
    void testEquals() {
        assertNotEquals(null, instance);
    }

    @Test
    void testGetLockCauseDetailWithRemoteLockAndNoBuildDoesNotThrow() {
        instance.setRemoteLockedBy("remote-lock-1");
        instance.setReservedTimestamp(new Date(0));

        String detail = assertDoesNotThrow(instance::getLockCauseDetail);

        assertNotNull(detail);
        assertTrue(detail.contains("remote-lock-1"));
    }

    @Test
    void testGetLockCauseWithReservedResource() {
        LockableResource resource = new LockableResource("reserved-res");
        resource.reserve("testUser");

        String cause = resource.getLockCause();

        assertNotNull(cause);
        assertTrue(cause.contains("reserved-res"), "Should contain resource name");
        assertTrue(cause.contains("testUser"), "Should contain reservedBy user");
        assertTrue(cause.contains("at"), "Should contain 'at' keyword");
        // Verify the ISO-like timestamp format: yyyy-MM-dd'T'HH:mm:ssZ
        assertTrue(
                Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{4}")
                        .matcher(cause)
                        .find(),
                "Should contain ISO-like timestamp, got: " + cause);
    }
}
