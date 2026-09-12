/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.remote.RemoteLockManager;
import org.jenkins.plugins.lockableresources.remote.RemoteLockRequest;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The audit log has to be worth trusting, because an oracle is going to be built on it.
 *
 * <p>Reconstructing mutual exclusion from the clients cannot work: a client timestamps its own call
 * to release, and that call returns after the server has already freed the resource and possibly
 * handed it to the next waiter. Two clients' logs can therefore appear to overlap on a resource that
 * was never held twice, and nothing in those logs distinguishes that from a real double-grant.
 *
 * <p>These lines are written where the state changes, under the lock that guards it, so their order
 * is the order the resource really changed hands. What is checked here is exactly that: the release
 * of one holder is recorded before the acquisition of the next, and each line names who it was.
 */
@WithJenkins
class ResourceAuditLogTest {

    private static final class Capture implements AutoCloseable {
        private final Logger logger = Logger.getLogger("org.jenkins.plugins.lockableresources.audit");
        private final List<String> lines = new ArrayList<>();
        private final Level previous = logger.getLevel();
        private final Handler handler;

        Capture() {
            handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    synchronized (lines) {
                        lines.add(record.getMessage());
                    }
                }

                @Override
                public void flush() {}

                @Override
                public void close() {}
            };
            handler.setLevel(Level.FINE);
            logger.setLevel(Level.FINE);
            logger.addHandler(handler);
        }

        List<String> lines() {
            synchronized (lines) {
                return new ArrayList<>(lines);
            }
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }

    private static RemoteLockRequest req(String resource) {
        return new RemoteLockRequest(resource, null, 0, null, false, "SEQUENTIAL", false, null, 0, 0, "MINUTES", null);
    }

    /** Fields of an LRA line: epochMs, kind, event, resource, holder. */
    private static String[] parse(String line) {
        assertTrue(line.startsWith("LRA|"), "unexpected audit line: " + line);
        String[] parts = line.split("\\|", -1);
        assertEquals(6, parts.length, "unexpected audit line shape: " + line);
        return parts;
    }

    @Test
    void aHandoverIsRecordedInTheOrderItHappened(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");

        try (Capture audit = new Capture()) {
            var first = RemoteLockManager.get().enqueue(req("board-1"), "client-a");
            var second = RemoteLockManager.get().enqueue(req("board-1"), "client-b");
            // The second one has to wait: that is what makes this a handover rather than two grants.
            assertEquals("QUEUED", second.getState().name());

            RemoteLockManager.get().release(first.getLockId());
            assertEquals("ACQUIRED", second.getState().name());
            RemoteLockManager.get().release(second.getLockId());

            List<String> board1 = new ArrayList<>();
            for (String line : audit.lines()) {
                String[] f = parse(line);
                if ("board-1".equals(f[4])) {
                    board1.add(f[3] + " " + f[5]); // event + holder
                }
            }

            // Four transitions, in this order, each naming the holder it belonged to. The release of
            // the first appears before the acquisition of the second - which is the fact a client-side
            // reconstruction cannot establish.
            assertEquals(
                    List.of(
                            "ACQUIRED " + first.getLockId(),
                            "RELEASED " + first.getLockId(),
                            "ACQUIRED " + second.getLockId(),
                            "RELEASED " + second.getLockId()),
                    board1);
        }
    }

    @Test
    void everyLineCarriesWhoHeldItAndHow(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");

        try (Capture audit = new Capture()) {
            var record = RemoteLockManager.get().enqueue(req("board-1"), "client-a");
            RemoteLockManager.get().release(record.getLockId());

            List<String> lines = audit.lines();
            assertTrue(lines.size() >= 2, "an acquire and a release were recorded: " + lines);
            for (String line : lines) {
                String[] f = parse(line);
                assertTrue(Long.parseLong(f[1]) > 0, "a timestamp to order by");
                assertEquals("REMOTE", f[2], "a remote hold says so, to keep it apart from a local one");
                assertTrue("ACQUIRED".equals(f[3]) || "RELEASED".equals(f[3]), "the transition itself: " + f[3]);
                assertEquals(record.getLockId(), f[5], "the holder, so client and server can be joined up");
            }
        }
    }

    @Test
    void nothingIsWrittenWhenTheLoggerIsOff(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");

        Logger logger = Logger.getLogger("org.jenkins.plugins.lockableresources.audit");
        Level previous = logger.getLevel();
        List<String> lines = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                lines.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.setLevel(Level.INFO); // the default: auditing is opt-in
        logger.addHandler(handler);
        try {
            var record = RemoteLockManager.get().enqueue(req("board-1"), "client-a");
            RemoteLockManager.get().release(record.getLockId());
            assertTrue(lines.isEmpty(), "ordinary use does not pay for the audit trail");
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }
}
