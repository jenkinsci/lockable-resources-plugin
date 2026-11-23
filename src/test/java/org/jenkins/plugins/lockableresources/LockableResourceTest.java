package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Date;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

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

    // -----------------------------------------------------------------------
    // Tests for invalid user lookup handling (no stack traces, clean messages)
    // -----------------------------------------------------------------------

    /**
     * Verifies that reserving a resource with a bogus username does not cause
     * {@code getLockCauseDetail()} to throw, and returns a clean message containing
     * the raw username instead of a stack trace.
     */
    @WithJenkins
    @Test
    void getLockCauseDetail_withBogusUser_doesNotThrow(JenkinsRule r) throws Exception {
        LockableResource resource = new LockableResource("myResource");
        resource.reserve("bogusUserName");

        String detail = assertDoesNotThrow(resource::getLockCauseDetail);

        assertNotNull(detail);
        assertTrue(detail.contains("myResource"), "Should contain resource name");
        assertTrue(detail.contains("bogusUserName"), "Should contain the raw reservedBy name");
        assertTrue(detail.contains("reserved"), "Should mention reservation");
        // Must not leak stack trace artifacts into the message
        assertFalse(detail.contains("Exception"), "Should not contain exception text in message");
        assertFalse(detail.contains("at "), "Should not contain stack trace frames in message");
    }

    /**
     * Verifies that {@code getReservedByEmail()} gracefully handles a bogus reservedBy
     * username and returns null without throwing.
     */
    @WithJenkins
    @Test
    void getReservedByEmail_withBogusUser_returnsNull(JenkinsRule r) throws Exception {
        LockableResource resource = new LockableResource("myResource");
        resource.reserve("bogusUserName");

        String email = assertDoesNotThrow(resource::getReservedByEmail);
        assertNull(email, "Email should be null for unknown user");
    }

    /**
     * Verifies that when Jenkins.getUser() throws (simulating an LDAP error),
     * a short message is logged at FINE level and the full stack trace at FINEST level,
     * without any log records above FINE (i.e., no WARNING or SEVERE spam in build logs).
     */
    @WithJenkins
    @Test
    void getLockCauseDetail_withBogusUser_logsAtFineAndFinest(JenkinsRule r) throws Exception {
        LockableResource resource = new LockableResource("myResource");
        resource.reserve("bogusUserName");

        Logger logger = Logger.getLogger(LockableResource.class.getName());
        Level originalLevel = logger.getLevel();
        TestLogHandler logHandler = new TestLogHandler();
        logHandler.setLevel(Level.ALL);
        logger.addHandler(logHandler);
        logger.setLevel(Level.ALL);

        try {
            // Use Mockito to create a spy that throws on getUser()
            Jenkins originalJenkins = Jenkins.get();
            Jenkins spyJenkins = org.mockito.Mockito.spy(originalJenkins);
            org.mockito.Mockito.doThrow(new RuntimeException("Simulated LDAP error"))
                    .when(spyJenkins)
                    .getUser(org.mockito.ArgumentMatchers.anyString());

            // Replace Jenkins singleton with the spy via reflection
            java.lang.reflect.Field instanceField = Jenkins.class.getDeclaredField("theInstance");
            instanceField.setAccessible(true);
            instanceField.set(null, spyJenkins);

            try {
                resource.getLockCauseDetail();
                resource.getReservedByEmail();

                // Each method emits 2 records: one FINE (short message) + one FINEST (full stack trace)
                // 2 methods x 2 records = 4 total
                assertEquals(4, logHandler.records.size(), "Expected 4 log records (2 FINE + 2 FINEST)");

                int fineCount = 0;
                int finestCount = 0;
                for (LogRecord record : logHandler.records) {
                    // Verify the log message mentions the reservedBy value
                    assertTrue(
                            record.getMessage().contains("bogusUserName"),
                            "Log message should mention the bogus username: " + record.getMessage());

                    if (record.getLevel().equals(Level.FINE)) {
                        fineCount++;
                        // FINE logs should have the short message with exception message, no thrown exception
                        assertNull(record.getThrown(), "FINE log record should not include the thrown exception");
                    } else if (record.getLevel().equals(Level.FINEST)) {
                        finestCount++;
                        // FINEST logs should include the full exception for stack trace
                        assertNotNull(
                                record.getThrown(),
                                "FINEST log record should include the thrown exception for stack trace");
                    } else {
                        fail("Unexpected log level " + record.getLevel() + ": " + record.getMessage());
                    }
                }

                assertEquals(2, fineCount, "Expected 2 FINE log records");
                assertEquals(2, finestCount, "Expected 2 FINEST log records");
            } finally {
                // Restore original Jenkins instance
                instanceField.set(null, originalJenkins);
            }
        } finally {
            logger.removeHandler(logHandler);
            if (originalLevel != null) {
                logger.setLevel(originalLevel);
            }
        }
    }

    /**
     * Simple log handler that captures LogRecords for assertion.
     */
    private static class TestLogHandler extends Handler {
        final java.util.List<LogRecord> records = new java.util.ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() throws SecurityException {}
    }
}
