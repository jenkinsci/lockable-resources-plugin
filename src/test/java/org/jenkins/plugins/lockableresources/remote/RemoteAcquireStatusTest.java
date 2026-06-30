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
}
