/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class RemoteLockManagerTest {

    @BeforeEach
    void setUp() {
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    }

    private static RemoteLockRequest req(String resource) {
        return new RemoteLockRequest(resource, null, 0, null, false, "SEQUENTIAL", false, null, 0, 0, "MINUTES", null);
    }

    private static RemoteLockRequest reqWithVar(String resource, String variable) {
        return new RemoteLockRequest(
                resource, null, 0, variable, false, "SEQUENTIAL", false, null, 0, 0, "MINUTES", null);
    }

    private static RemoteLockRequest labelReq(String label, int quantity, boolean skipIfLocked) {
        return new RemoteLockRequest(
                null, label, quantity, null, false, "SEQUENTIAL", skipIfLocked, null, 0, 0, "MINUTES", null);
    }

    private static RemoteLockRequest labelReqWithVar(String label, int quantity, String variable) {
        return new RemoteLockRequest(
                null, label, quantity, variable, false, "SEQUENTIAL", false, null, 0, 0, "MINUTES", null);
    }

    private static RemoteLockRequest reqWithTimeout(String resource, long timeout, String unit) {
        return new RemoteLockRequest(resource, null, 0, null, false, "SEQUENTIAL", false, null, 0, timeout, unit, null);
    }

    @Test
    void enqueueRejectsUnknownResourceAndCreatesNothing(JenkinsRule j) {
        // M1E: a selector this client can't lock (unknown/unexposed) is rejected up front (→ 404), and the
        // remote request must NOT create an ephemeral resource on the server (H-1 regression guard).
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);

        RemoteLockRecord record = RemoteLockManager.get().enqueue(req("missing-resource"), null);

        assertEquals(RemoteLockState.FAILED, record.getState());
        assertEquals("UNKNOWN_RESOURCE", record.getErrorCode());
        assertNull(manager.fromName("missing-resource"), "remote acquire must not create the resource");
    }

    @Test
    void enqueueAcquiresSingleFreeResource(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("remote-ok");
        manager.createResourceWithLabel("res-a", "remote-ok");

        RemoteLockRecord record = RemoteLockManager.get().enqueue(req("res-a"), null);

        assertEquals(RemoteLockState.ACQUIRED, record.getState());
        assertNotNull(record.getAcquiredResourceNames());
        assertEquals(List.of("res-a"), record.getAcquiredResourceNames());
    }

    @Test
    void enqueueQueuesWhenResourceBusy(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("remote-ok");
        manager.createResourceWithLabel("res-b", "remote-ok");

        RemoteLockRecord first = RemoteLockManager.get().enqueue(req("res-b"), null);
        assertEquals(RemoteLockState.ACQUIRED, first.getState());

        RemoteLockRecord second = RemoteLockManager.get().enqueue(req("res-b"), null);
        assertEquals(RemoteLockState.QUEUED, second.getState());
    }

    @Test
    void enqueueSkipsWhenBusyAndSkipIfLocked(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("remote-ok");
        manager.createResourceWithLabel("res-c", "remote-ok");

        RemoteLockRecord first = RemoteLockManager.get().enqueue(req("res-c"), null);
        assertEquals(RemoteLockState.ACQUIRED, first.getState());

        RemoteLockRequest skipReq =
                new RemoteLockRequest("res-c", null, 0, null, false, "SEQUENTIAL", true, null, 0, 0, "MINUTES", null);
        RemoteLockRecord second = RemoteLockManager.get().enqueue(skipReq, null);
        assertEquals(RemoteLockState.SKIPPED, second.getState());
    }

    @Test
    void releaseFreesSingleResource(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("remote-ok");
        manager.createResourceWithLabel("res-d", "remote-ok");

        RemoteLockRecord record = RemoteLockManager.get().enqueue(req("res-d"), null);
        assertEquals(RemoteLockState.ACQUIRED, record.getState());

        LockableResource resource = manager.fromName("res-d");
        assertNotNull(resource);
        assertNotNull(resource.getRemoteLockedBy());

        RemoteLockManager.get().release(record.getLockId());
        assertNull(resource.getRemoteLockedBy());
    }

    @Test
    void enqueueAcquiresByLabelWhenResourcesExposed(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("board");
        manager.createResourceWithLabel("board-1", "board");
        manager.createResourceWithLabel("board-2", "board");

        RemoteLockRecord record = RemoteLockManager.get().enqueue(labelReq("board", 1, false), null);

        assertEquals(RemoteLockState.ACQUIRED, record.getState());
        assertNotNull(record.getAcquiredResourceNames());
        assertEquals(1, record.getAcquiredResourceNames().size());
        assertTrue(record.getAcquiredResourceNames().get(0).startsWith("board-"));
    }

    @Test
    void enqueueAcquiresQuantityByLabel(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("board");
        manager.createResourceWithLabel("board-1", "board");
        manager.createResourceWithLabel("board-2", "board");

        RemoteLockRecord record = RemoteLockManager.get().enqueue(labelReq("board", 2, false), null);

        assertEquals(RemoteLockState.ACQUIRED, record.getState());
        assertNotNull(record.getAcquiredResourceNames());
        assertEquals(2, record.getAcquiredResourceNames().size());
    }

    @Test
    void enqueueQueuesWhenInsufficientFreeByLabel(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("board");
        manager.createResourceWithLabel("board-1", "board");

        // Lock the one resource
        RemoteLockRecord first = RemoteLockManager.get().enqueue(labelReq("board", 1, false), null);
        assertEquals(RemoteLockState.ACQUIRED, first.getState());

        // Try to acquire another — should queue
        RemoteLockRecord second = RemoteLockManager.get().enqueue(labelReq("board", 1, false), null);
        assertEquals(RemoteLockState.QUEUED, second.getState());
    }

    @Test
    void enqueueSkipsByLabelWhenBusyAndSkipIfLocked(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("board");
        manager.createResourceWithLabel("board-1", "board");

        RemoteLockRecord first = RemoteLockManager.get().enqueue(labelReq("board", 1, false), null);
        assertEquals(RemoteLockState.ACQUIRED, first.getState());

        RemoteLockRecord second = RemoteLockManager.get().enqueue(labelReq("board", 1, true), null);
        assertEquals(RemoteLockState.SKIPPED, second.getState());
    }

    @Test
    void enqueueRejectsUnknownLabel(JenkinsRule j) {
        // M1E: a label with no exposed candidate is rejected up front (→ 404 UNKNOWN_LABEL), not queued.
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("board");

        RemoteLockRecord record = RemoteLockManager.get().enqueue(labelReq("no-such-label", 1, false), null);
        assertEquals(RemoteLockState.FAILED, record.getState());
        assertEquals("UNKNOWN_LABEL", record.getErrorCode());
    }

    @Test
    void lockEnvVarsIsNullWhenVariableNotSet(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("board");
        manager.createResourceWithLabel("board-1", "board");

        RemoteLockRecord record = RemoteLockManager.get().enqueue(req("board-1"), null);
        assertEquals(RemoteLockState.ACQUIRED, record.getState());
        assertNull(record.getLockEnvVars());
    }

    @Test
    void lockEnvVarsContainsCorrectEntriesWhenVariableIsSet(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("board");
        manager.createResourceWithLabel("board-1", "board");

        RemoteLockRecord record = RemoteLockManager.get().enqueue(reqWithVar("board-1", "MY_RES"), null);
        assertEquals(RemoteLockState.ACQUIRED, record.getState());
        assertNotNull(record.getLockEnvVars());
        assertEquals("board-1", record.getLockEnvVars().get("MY_RES"));
        assertEquals("board-1", record.getLockEnvVars().get("MY_RES0"));
        assertEquals(2, record.getLockEnvVars().size());
    }

    @Test
    void lockEnvVarsForMultiResourceLabelContainsAllEntries(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("board");
        manager.createResourceWithLabel("board-1", "board");
        manager.createResourceWithLabel("board-2", "board");

        RemoteLockRecord record = RemoteLockManager.get().enqueue(labelReqWithVar("board", 2, "BOARDS"), null);
        assertEquals(RemoteLockState.ACQUIRED, record.getState());
        assertNotNull(record.getLockEnvVars());
        // Combined value is both resources comma-separated (matching local lock() semantics)
        String combined = record.getLockEnvVars().get("BOARDS");
        assertNotNull(combined);
        assertTrue(combined.contains("board-1") || combined.contains("board-2"));
        // Individual entries present
        assertTrue(record.getLockEnvVars().containsKey("BOARDS0"));
        assertTrue(record.getLockEnvVars().containsKey("BOARDS1"));
        assertEquals(3, record.getLockEnvVars().size());
    }

    @Test
    void releaseFreesAllAcquiredResourcesByLabel(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("board");
        manager.createResourceWithLabel("board-1", "board");
        manager.createResourceWithLabel("board-2", "board");

        RemoteLockRecord record = RemoteLockManager.get().enqueue(labelReq("board", 2, false), null);
        assertEquals(RemoteLockState.ACQUIRED, record.getState());

        // Both resources should be locked
        LockableResource r1 = manager.fromName("board-1");
        LockableResource r2 = manager.fromName("board-2");
        assertNotNull(r1.getRemoteLockedBy());
        assertNotNull(r2.getRemoteLockedBy());

        RemoteLockManager.get().release(record.getLockId());

        assertNull(r1.getRemoteLockedBy());
        assertNull(r2.getRemoteLockedBy());
    }

    @Test
    void extraResourcesAreLockedAtomically(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");
        manager.createResourceWithLabel("board-2", "hw");

        List<RemoteLockRequest.ExtraResource> extra = List.of(new RemoteLockRequest.ExtraResource("board-2", null, 0));
        RemoteLockRequest req = new RemoteLockRequest(
                "board-1", null, 0, "RES", false, "SEQUENTIAL", false, extra, 0, 0, "MINUTES", null);

        RemoteLockRecord record = RemoteLockManager.get().enqueue(req, null);
        assertEquals(RemoteLockState.ACQUIRED, record.getState());
        assertEquals(2, record.getAcquiredResourceNames().size());

        LockableResource r1 = manager.fromName("board-1");
        LockableResource r2 = manager.fromName("board-2");
        assertNotNull(r1.getRemoteLockedBy());
        assertNotNull(r2.getRemoteLockedBy());

        // lockEnvVars combined value uses comma separator (matching local lock() semantics)
        String combined = record.getLockEnvVars().get("RES");
        assertNotNull(combined);
        assertTrue(combined.contains(","), "Combined lockEnvVar should use comma separator");

        RemoteLockManager.get().release(record.getLockId());
        assertNull(r1.getRemoteLockedBy());
        assertNull(r2.getRemoteLockedBy());
    }

    @Test
    void extraResourceNotAcquiredWhenOneIsBusy(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");
        manager.createResourceWithLabel("board-2", "hw");

        // Pre-lock board-2
        RemoteLockRecord first = RemoteLockManager.get().enqueue(req("board-2"), null);
        assertEquals(RemoteLockState.ACQUIRED, first.getState());

        // Request board-1 + board-2 atomically — should stay QUEUED (board-2 busy)
        List<RemoteLockRequest.ExtraResource> extra = List.of(new RemoteLockRequest.ExtraResource("board-2", null, 0));
        RemoteLockRequest req = new RemoteLockRequest(
                "board-1", null, 0, null, false, "SEQUENTIAL", false, extra, 0, 0, "MINUTES", null);
        RemoteLockRecord second = RemoteLockManager.get().enqueue(req, null);
        assertEquals(RemoteLockState.QUEUED, second.getState());

        // board-1 must not have been locked
        assertNull(manager.fromName("board-1").getRemoteLockedBy());
    }

    @Test
    void queuedEntryBecomesAcquiredWhenResourceFreed(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");

        // Acquire board-1
        RemoteLockRecord first = RemoteLockManager.get().enqueue(req("board-1"), null);
        assertEquals(RemoteLockState.ACQUIRED, first.getState());

        // Second request queued
        RemoteLockRecord second = RemoteLockManager.get().enqueue(req("board-1"), null);
        assertEquals(RemoteLockState.QUEUED, second.getState());

        // Release first — second should become ACQUIRED via LRM proceedNextContext
        RemoteLockManager.get().release(first.getLockId());
        assertEquals(RemoteLockState.ACQUIRED, second.getState());
        assertNotNull(manager.fromName("board-1").getRemoteLockedBy());
    }

    @Test
    void queuedRecordExpiresViaQueueTimeout(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");

        RemoteLockRecord first = RemoteLockManager.get().enqueue(req("board-1"), null);
        assertEquals(RemoteLockState.ACQUIRED, first.getState());

        // QUEUED with a short allocate timeout. B2: expiry is owned entirely by the unified queue
        // (RemoteQueueEntry deadline = timeoutForAllocateResource), not by client polling.
        RemoteLockRecord second =
                RemoteLockManager.get().enqueue(reqWithTimeout("board-1", 200L, "MILLISECONDS"), null);
        assertEquals(RemoteLockState.QUEUED, second.getState());

        // After the deadline, a queue maintenance pass (proceedNextContext -> getNextRemoteEntry)
        // fails the entry on timeout — no polling is involved.
        Thread.sleep(400);
        manager.checkTimeouts();

        assertEquals(RemoteLockState.FAILED, second.getState());
        assertEquals("LOCK_WAIT_TIMEOUT", second.getErrorCode());

        // The timed-out entry must NOT grab the resource once it frees up.
        RemoteLockManager.get().release(first.getLockId());
        assertNull(manager.fromName("board-1").getRemoteLockedBy());
    }

    @Test
    void queuedRecordWithoutTimeoutSurvivesWithoutPolling(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");

        RemoteLockRecord first = RemoteLockManager.get().enqueue(req("board-1"), null);
        assertEquals(RemoteLockState.ACQUIRED, first.getState());

        // No allocate timeout (req() => 0 = wait forever) and the client never polls.
        RemoteLockRecord second = RemoteLockManager.get().enqueue(req("board-1"), null);
        assertEquals(RemoteLockState.QUEUED, second.getState());

        // B2: the GET poll no longer keeps the entry alive, so background scans must NOT
        // expire an un-polled QUEUED record (the old poll-keepalive GC is gone).
        for (int i = 0; i < 3; i++) {
            Thread.sleep(50);
            RemoteLockManager.get().doRun();
        }
        assertEquals(RemoteLockState.QUEUED, second.getState());

        // find() is the GET status path: a pure read that must not change state.
        assertEquals(
                RemoteLockState.QUEUED,
                RemoteLockManager.get().find(second.getLockId()).getState());

        // Promotion via the unified queue still works when the resource frees up.
        RemoteLockManager.get().release(first.getLockId());
        assertEquals(RemoteLockState.ACQUIRED, second.getState());
    }

    @Test
    void releaseSchedulesQueueMaintenanceForLocalWaiters(JenkinsRule j) {
        // Verify that release() does not throw and clears remoteLockedBy
        // (full local-waiter wake-up is tested by integration; this is a smoke check)
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");

        RemoteLockRecord record = RemoteLockManager.get().enqueue(req("board-1"), null);
        assertEquals(RemoteLockState.ACQUIRED, record.getState());
        assertNotNull(manager.fromName("board-1").getRemoteLockedBy());

        RemoteLockManager.get().release(record.getLockId());
        assertNull(manager.fromName("board-1").getRemoteLockedBy());
    }

    // -----------------------------------------------------------------------
    // M1C C-1: label-based extra entries (the M1B silent-drop regression)
    // -----------------------------------------------------------------------

    private static RemoteLockRequest reqWithExtra(
            String resource, String label, int quantity, String variable, List<RemoteLockRequest.ExtraResource> extra) {
        return new RemoteLockRequest(
                resource, label, quantity, variable, false, "SEQUENTIAL", false, extra, 0, 0, "MINUTES", null);
    }

    @Test
    void resourceWithExtraLabelEntryIsAcquiredAtomically(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("remote-ok");
        manager.createResourceWithLabel("board-1", "remote-ok");
        manager.createResourceWithLabel("gpu-1", "gpu remote-ok");

        // lock(resource: 'board-1', extra: [[label: 'gpu', quantity: 1]])
        List<RemoteLockRequest.ExtraResource> extra = List.of(new RemoteLockRequest.ExtraResource(null, "gpu", 1));
        RemoteLockRecord record = RemoteLockManager.get().enqueue(reqWithExtra("board-1", null, 0, null, extra), null);

        assertEquals(RemoteLockState.ACQUIRED, record.getState());
        assertEquals(2, record.getAcquiredResourceNames().size());
        assertTrue(record.getAcquiredResourceNames().contains("board-1"));
        assertTrue(record.getAcquiredResourceNames().contains("gpu-1"));
        // The label-based extra resource MUST actually be locked (M1B dropped it silently).
        assertNotNull(manager.fromName("board-1").getRemoteLockedBy());
        assertNotNull(manager.fromName("gpu-1").getRemoteLockedBy());
    }

    @Test
    void resourceWithExtraLabelStaysQueuedWhenLabelBusy(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("remote-ok");
        manager.createResourceWithLabel("board-1", "remote-ok");
        manager.createResourceWithLabel("gpu-1", "gpu remote-ok");

        // Pre-lock the only gpu resource
        RemoteLockRecord first = RemoteLockManager.get().enqueue(labelReq("gpu", 1, false), null);
        assertEquals(RemoteLockState.ACQUIRED, first.getState());

        List<RemoteLockRequest.ExtraResource> extra = List.of(new RemoteLockRequest.ExtraResource(null, "gpu", 1));
        RemoteLockRecord second = RemoteLockManager.get().enqueue(reqWithExtra("board-1", null, 0, null, extra), null);

        // Atomicity: nothing is partially locked while the extra label is unavailable
        assertEquals(RemoteLockState.QUEUED, second.getState());
        assertNull(manager.fromName("board-1").getRemoteLockedBy());
    }

    // Note: a degenerate "main label X + extra label X" (same label) request is intentionally NOT
    // pinned by a test. M1D resolves it through the SAME canonical path as local
    // lock(label:'X', extra:[[label:'X']]), whose cross-selector same-label behaviour is a known local
    // quirk (the extra's count is partly swallowed; see LockableResourcesManager getAvailableResources
    // isPreReserved FIXME). Remote now matches local exactly there — that is the point of M1D — so we do
    // not encode a remote-specific expectation for that degenerate case. The meaningful label-extra
    // coverage is resource + a DIFFERENT-label extra (resourceWithExtraLabelEntryIsAcquiredAtomically).

    @Test
    void extraLabelWithNoExposedCandidateFails(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("remote-ok");
        manager.createResourceWithLabel("board-1", "remote-ok");

        List<RemoteLockRequest.ExtraResource> extra =
                List.of(new RemoteLockRequest.ExtraResource(null, "no-such-label", 1));
        RemoteLockRecord record = RemoteLockManager.get().enqueue(reqWithExtra("board-1", null, 0, null, extra), null);

        // M1E: an extra label with no exposed candidate is rejected up front (→ 404 UNKNOWN_LABEL);
        // nothing is partially locked.
        assertEquals(RemoteLockState.FAILED, record.getState());
        assertEquals("UNKNOWN_LABEL", record.getErrorCode());
        assertNull(manager.fromName("board-1").getRemoteLockedBy());
    }

    @Test
    void queuedExtraLabelIsPromotedWhenLabelFreed(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("remote-ok");
        manager.createResourceWithLabel("board-1", "remote-ok");
        manager.createResourceWithLabel("gpu-1", "gpu remote-ok");

        RemoteLockRecord first = RemoteLockManager.get().enqueue(labelReq("gpu", 1, false), null);
        assertEquals(RemoteLockState.ACQUIRED, first.getState());

        List<RemoteLockRequest.ExtraResource> extra = List.of(new RemoteLockRequest.ExtraResource(null, "gpu", 1));
        RemoteLockRecord second = RemoteLockManager.get().enqueue(reqWithExtra("board-1", null, 0, null, extra), null);
        assertEquals(RemoteLockState.QUEUED, second.getState());

        // Freeing the gpu must promote the queued resource+label-extra request via the unified queue
        RemoteLockManager.get().release(first.getLockId());
        assertEquals(RemoteLockState.ACQUIRED, second.getState());
        assertNotNull(manager.fromName("board-1").getRemoteLockedBy());
        assertNotNull(manager.fromName("gpu-1").getRemoteLockedBy());
    }

    // -----------------------------------------------------------------------
    // M1C M-2: extra-only requests (local lock() permits them)
    // -----------------------------------------------------------------------

    @Test
    void extraOnlyRequestAcquiresExtraResources(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("remote-ok");
        manager.createResourceWithLabel("board-1", "remote-ok");

        // lock(extra: [[resource: 'board-1']]) — no main resource/label
        List<RemoteLockRequest.ExtraResource> extra = List.of(new RemoteLockRequest.ExtraResource("board-1", null, 0));
        RemoteLockRecord record = RemoteLockManager.get().enqueue(reqWithExtra(null, null, 0, null, extra), null);

        assertEquals(RemoteLockState.ACQUIRED, record.getState());
        assertEquals(List.of("board-1"), record.getAcquiredResourceNames());
        assertNotNull(manager.fromName("board-1").getRemoteLockedBy());
    }

    // -----------------------------------------------------------------------
    // M1C C-2: releasing a QUEUED record must exclude it from later promotion
    // -----------------------------------------------------------------------

    @Test
    void releasingQueuedRecordPreventsLaterPromotion(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("board-1", "hw");

        RemoteLockRecord first = RemoteLockManager.get().enqueue(req("board-1"), null);
        assertEquals(RemoteLockState.ACQUIRED, first.getState());

        RemoteLockRecord queued = RemoteLockManager.get().enqueue(req("board-1"), null);
        assertEquals(RemoteLockState.QUEUED, queued.getState());

        // Release the QUEUED record: it must be terminal-marked so a later promotion cannot grab
        // the resource for an already-released record (the orphan-lock race, M1B 4-5 redux).
        RemoteLockManager.get().release(queued.getLockId());
        assertEquals(RemoteLockState.FAILED, queued.getState());
        assertEquals("RELEASED", queued.getErrorCode());

        // Now free board-1 — the released queue entry must NOT acquire it.
        RemoteLockManager.get().release(first.getLockId());
        assertNull(manager.fromName("board-1").getRemoteLockedBy());
    }

    // -----------------------------------------------------------------------
    // M1C follow-up: label with unspecified quantity (0) means ALL matching,
    // equivalent to local lock() ("0 means all"). Previously remote locked only 1.
    // -----------------------------------------------------------------------

    @Test
    void bareLabelLocksAllMatchingResources(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("board");
        manager.createResourceWithLabel("board-1", "board");
        manager.createResourceWithLabel("board-2", "board");
        manager.createResourceWithLabel("board-3", "board");

        // quantity 0 (unspecified) → lock ALL three, like local lock(label: 'board')
        RemoteLockRecord record = RemoteLockManager.get().enqueue(labelReq("board", 0, false), null);

        assertEquals(RemoteLockState.ACQUIRED, record.getState());
        assertEquals(3, record.getAcquiredResourceNames().size());
        assertNotNull(manager.fromName("board-1").getRemoteLockedBy());
        assertNotNull(manager.fromName("board-2").getRemoteLockedBy());
        assertNotNull(manager.fromName("board-3").getRemoteLockedBy());
    }

    @Test
    void bareLabelStaysQueuedUntilAllFreeThenLocksAll(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("board");
        manager.createResourceWithLabel("board-1", "board");
        manager.createResourceWithLabel("board-2", "board");

        // Pre-lock one of the two
        RemoteLockRecord holder = RemoteLockManager.get().enqueue(req("board-1"), null);
        assertEquals(RemoteLockState.ACQUIRED, holder.getState());

        // "all board" cannot proceed while board-1 is busy → QUEUED (no partial lock of board-2)
        RemoteLockRecord all = RemoteLockManager.get().enqueue(labelReq("board", 0, false), null);
        assertEquals(RemoteLockState.QUEUED, all.getState());
        assertNull(manager.fromName("board-2").getRemoteLockedBy());

        // Free board-1 → the "all" request now acquires BOTH
        RemoteLockManager.get().release(holder.getLockId());
        assertEquals(RemoteLockState.ACQUIRED, all.getState());
        assertEquals(2, all.getAcquiredResourceNames().size());
        assertNotNull(manager.fromName("board-1").getRemoteLockedBy());
        assertNotNull(manager.fromName("board-2").getRemoteLockedBy());
    }

    @Test
    void bareLabelExtraLocksAllMatching(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("remote-ok");
        manager.createResourceWithLabel("main-1", "remote-ok");
        manager.createResourceWithLabel("gpu-1", "gpu remote-ok");
        manager.createResourceWithLabel("gpu-2", "gpu remote-ok");

        // main resource + extra [label gpu, quantity 0] → lock main + ALL gpu
        List<RemoteLockRequest.ExtraResource> extra = List.of(new RemoteLockRequest.ExtraResource(null, "gpu", 0));
        RemoteLockRecord record = RemoteLockManager.get().enqueue(reqWithExtra("main-1", null, 0, null, extra), null);

        assertEquals(RemoteLockState.ACQUIRED, record.getState());
        assertEquals(3, record.getAcquiredResourceNames().size());
        assertNotNull(manager.fromName("main-1").getRemoteLockedBy());
        assertNotNull(manager.fromName("gpu-1").getRemoteLockedBy());
        assertNotNull(manager.fromName("gpu-2").getRemoteLockedBy());
    }

    @Test
    void labelQuantityExceedingPoolStaysQueued(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("board");
        manager.createResourceWithLabel("board-1", "board");
        manager.createResourceWithLabel("board-2", "board");

        // quantity 3 with only 2 candidates → unsatisfiable, stays QUEUED (no partial lock), as local
        RemoteLockRecord record = RemoteLockManager.get().enqueue(labelReq("board", 3, false), null);
        assertEquals(RemoteLockState.QUEUED, record.getState());
        assertNull(manager.fromName("board-1").getRemoteLockedBy());
        assertNull(manager.fromName("board-2").getRemoteLockedBy());
    }

    // -----------------------------------------------------------------------
    // M1D: canonical-path delegation makes resource-property env vars and selectStrategy transparent.
    // M1E: exposure is the exposeLabel set (OR); unknown/unexposed selectors are rejected (→ 404).
    // -----------------------------------------------------------------------

    @Test
    void resourcePropertyEnvVarsArePropagated(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabelAndProperties("board-1", "hw", java.util.Map.of("IP", "10.0.0.7"));

        RemoteLockRecord record = RemoteLockManager.get().enqueue(reqWithVar("board-1", "RES"), null);

        assertEquals(RemoteLockState.ACQUIRED, record.getState());
        assertNotNull(record.getLockEnvVars());
        assertEquals("board-1", record.getLockEnvVars().get("RES"));
        assertEquals("board-1", record.getLockEnvVars().get("RES0"));
        // M1D: per-resource property env var, identical to local lock() (previously dropped by remote).
        assertEquals("10.0.0.7", record.getLockEnvVars().get("RES0_IP"));
    }

    @Test
    void propertyEnvVarsArePropagatedOnQueuePromotion(JenkinsRule j) {
        // L-3/L-5: the QUEUED→promotion path (RemoteQueueEntry.onAcquired) must build the same env vars,
        // including resource-property env vars, as the immediate-acquire path.
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabelAndProperties("board-1", "hw", java.util.Map.of("IP", "10.9.8.37"));

        RemoteLockRecord holder = RemoteLockManager.get().enqueue(req("board-1"), null);
        assertEquals(RemoteLockState.ACQUIRED, holder.getState());

        RemoteLockRecord waiter = RemoteLockManager.get().enqueue(reqWithVar("board-1", "RES"), null);
        assertEquals(RemoteLockState.QUEUED, waiter.getState());

        RemoteLockManager.get().release(holder.getLockId()); // promotes waiter via onAcquired
        assertEquals(RemoteLockState.ACQUIRED, waiter.getState());
        assertNotNull(waiter.getLockEnvVars());
        assertEquals("board-1", waiter.getLockEnvVars().get("RES0"));
        assertEquals("10.9.8.37", waiter.getLockEnvVars().get("RES0_IP"));
    }

    @Test
    void unexposedNamedResourceIsRejected(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("hw");
        manager.createResourceWithLabel("public-1", "hw"); // carries exposeLabel → exposed
        manager.createResource("internal-1"); // no exposeLabel → not exposed

        // exposed resource is acquired
        RemoteLockRecord exposed = RemoteLockManager.get().enqueue(req("public-1"), null);
        assertEquals(RemoteLockState.ACQUIRED, exposed.getState());

        // a resource that exists but is NOT exposed is rejected up front (→ 404 UNKNOWN_RESOURCE), not queued
        RemoteLockRecord hidden = RemoteLockManager.get().enqueue(req("internal-1"), null);
        assertEquals(RemoteLockState.FAILED, hidden.getState());
        assertEquals("UNKNOWN_RESOURCE", hidden.getErrorCode());
        assertNull(manager.fromName("internal-1").getRemoteLockedBy());
    }

    @Test
    void multipleExposeLabelsAreOredForExposure(JenkinsRule j) {
        // M1E: exposeLabel is a whitespace-separated set; a resource is exposed if it carries ANY of them.
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("gpu license");
        manager.createResourceWithLabel("gpu-1", "gpu");
        manager.createResourceWithLabel("lic-1", "license");
        manager.createResourceWithLabel("other-1", "other");

        // carries one of the exposeLabels → exposed and acquired
        assertEquals(
                RemoteLockState.ACQUIRED,
                RemoteLockManager.get().enqueue(req("gpu-1"), null).getState());
        assertEquals(
                RemoteLockState.ACQUIRED,
                RemoteLockManager.get().enqueue(req("lic-1"), null).getState());

        // carries none of the exposeLabels → rejected
        RemoteLockRecord other = RemoteLockManager.get().enqueue(req("other-1"), null);
        assertEquals(RemoteLockState.FAILED, other.getState());
        assertEquals("UNKNOWN_RESOURCE", other.getErrorCode());
    }
}
