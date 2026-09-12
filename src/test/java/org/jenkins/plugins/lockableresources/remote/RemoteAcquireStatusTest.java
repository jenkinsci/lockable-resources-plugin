/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RemoteAcquireStatusTest {

    @Test
    void testNullStateFallsBackToUnknown() {
        RemoteAcquireStatus status = new RemoteAcquireStatus("lock-1", null, null, null, null);

        assertEquals(RemoteAcquireState.UNKNOWN, status.getState());
    }

    @Test
    void aStateThisClientDoesNotRecogniseReadsAsUnknown() {
        // Version skew: a newer server may report a state this client has never heard of. UNKNOWN is
        // handled as a failure, so the build stops rather than carrying on against a lock whose
        // condition nobody here can describe. Throwing instead would turn a forwards-compatible
        // server into a broken one.
        assertEquals(RemoteAcquireState.UNKNOWN, RemoteAcquireState.fromString("SOMETHING_NEW"));
        assertEquals(RemoteAcquireState.UNKNOWN, RemoteAcquireState.fromString(""));
        assertEquals(RemoteAcquireState.UNKNOWN, RemoteAcquireState.fromString(null));

        // Recognised states survive the trip, whatever case and padding the wire used.
        assertEquals(RemoteAcquireState.ACQUIRED, RemoteAcquireState.fromString("ACQUIRED"));
        assertEquals(RemoteAcquireState.QUEUED, RemoteAcquireState.fromString("queued"));
        assertEquals(RemoteAcquireState.FAILED, RemoteAcquireState.fromString("  Failed  "));
    }
}
