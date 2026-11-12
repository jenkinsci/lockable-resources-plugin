package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * {@link LockableResourcesManager#flushPendingSave()} runs as a {@code @Terminator} and must never
 * lose the state of the coalesced async save.
 */
@WithJenkins
class AsyncSaveShutdownTest {

    private static final String SAVE_THREAD = "lockable-resources-async-save";

    /**
     * Reproduces the lost save on shutdown: the coalesced {@code doSave()} is already running (it
     * has cleared {@code savePending} and is waiting for {@code syncResources}) when {@code
     * flushPendingSave()} is called. Interrupting that thread makes the subsequent XML write fail
     * with a {@code ClosedByInterruptException} and nothing re-saves the state.
     */
    @Test
    void flushPendingSaveDoesNotInterruptRunningSave(JenkinsRule j) throws Exception {
        System.clearProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE);
        LockableResourcesManager manager = LockableResourcesManager.get();

        Thread flusher = new Thread(LockableResourcesManager::flushPendingSave, "test-flush");
        synchronized (LockableResourcesManager.syncResources) {
            // schedules the async save
            assertTrue(manager.createResource("protected"));

            // wait until doSave() runs and blocks on syncResources held by this thread
            Thread saver = waitForThread(SAVE_THREAD, Thread.State.BLOCKED);
            assertNotNull(saver, "async save thread did not start");

            flusher.start();
            // give flushPendingSave() time to shut the executor down and start waiting
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline
                    && flusher.getState() != Thread.State.TIMED_WAITING
                    && flusher.getState() != Thread.State.TERMINATED) {
                Thread.sleep(20);
            }
        }
        flusher.join(60_000);
        assertFalse(flusher.isAlive(), "flushPendingSave() did not return");
        // the running save must complete after the lock is released
        waitForThread(SAVE_THREAD, Thread.State.TERMINATED);

        assertTrue(readConfig(j).contains("<name>protected</name>"), "resource missing from saved XML");
    }

    /** A save that was scheduled but has not started yet must be flushed synchronously. */
    @Test
    void flushPendingSaveRunsScheduledSave(JenkinsRule j) throws Exception {
        System.clearProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE);
        LockableResourcesManager manager = LockableResourcesManager.get();

        assertTrue(manager.createResource("scheduled"));
        LockableResourcesManager.flushPendingSave();

        assertTrue(readConfig(j).contains("<name>scheduled</name>"), "resource missing from saved XML");
    }

    private static String readConfig(JenkinsRule j) throws Exception {
        File xml = new File(j.jenkins.getRootDir(), LockableResourcesManager.class.getName() + ".xml");
        assertTrue(xml.isFile(), "config file was not written: " + xml);
        return Files.readString(xml.toPath(), StandardCharsets.UTF_8);
    }

    private static Thread waitForThread(String name, Thread.State state) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Thread found = null;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (name.equals(t.getName())) {
                    found = t;
                    if (t.getState() == state) {
                        return t;
                    }
                }
            }
            if (found == null && state == Thread.State.TERMINATED) {
                return null;
            }
            Thread.sleep(20);
        }
        return null;
    }
}
