package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Date;
import org.junit.jupiter.api.Test;

class LockableResourceTest {

    private final LockableResource instance = new LockableResource("r1");

    // Not sure how useful this is...
    @Test
    void testGetters() {
        assertEquals("r1", instance.getName());
        assertEquals("", instance.getDescription());
        assertEquals("", instance.getLabels());
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
}
