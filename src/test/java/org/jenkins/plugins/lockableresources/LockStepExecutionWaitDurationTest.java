package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Focused tests for the "Lock acquired ... after H:MM:SS" log suffix
 * (issue #178 follow-up review on PR #1108):
 * <ul>
 *     <li>a queued acquisition reports its waited duration and formatting;</li>
 *     <li>an immediate acquisition reports 0:00:00;</li>
 *     <li>a queued entry whose wait start is unknown (serialized by an older
 *     plugin version, timestamp field 0) reports an unknown duration
 *     instead of a misleading 0:00:00.</li>
 * </ul>
 */
class LockStepExecutionWaitDurationTest {

    // -----------------------------------------------------------------------
    // formatWaitDuration()
    // -----------------------------------------------------------------------

    @Test
    void formatWaitDuration() {
        assertEquals("0:00:00", LockStepExecution.formatWaitDuration(0));
        assertEquals("0:00:01", LockStepExecution.formatWaitDuration(1000));
        assertEquals("0:01:01", LockStepExecution.formatWaitDuration(61_000));
        assertEquals("1:01:01", LockStepExecution.formatWaitDuration(3_661_000));
        assertEquals("0:00:00", LockStepExecution.formatWaitDuration(-5000));
    }

    // -----------------------------------------------------------------------
    // formatWaitSuffix(): queued acquisition
    // -----------------------------------------------------------------------

    @Test
    void formatWaitSuffix_queuedAcquisitionReportsDuration() {
        long waitStartMillis = System.currentTimeMillis() - 65_000;
        String suffix = LockStepExecution.formatWaitSuffix(waitStartMillis, true);
        // 65 seconds of waiting: 0:01:05, allowing a second-boundary tick to 0:01:06.
        assertTrue(suffix.startsWith(" after 0:01:0"), () -> "expected a ~65s duration suffix, got: " + suffix);
    }

    // -----------------------------------------------------------------------
    // formatWaitSuffix(): immediate acquisition
    // -----------------------------------------------------------------------

    @Test
    void formatWaitSuffix_immediateAcquisitionReportsZero() {
        assertEquals(" after 0:00:00", LockStepExecution.formatWaitSuffix(0, true));
    }

    // -----------------------------------------------------------------------
    // formatWaitSuffix(): unknown wait start (pre-change persisted entry)
    // -----------------------------------------------------------------------

    @Test
    void formatWaitSuffix_unknownWaitStartReportsUnknownDuration() {
        assertEquals(" after unknown duration", LockStepExecution.formatWaitSuffix(0, false));
        assertEquals(" after unknown duration", LockStepExecution.formatWaitSuffix(-1, false));
    }
}
