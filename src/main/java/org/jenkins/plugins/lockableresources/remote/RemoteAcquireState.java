/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import java.util.Locale;

/**
 * Acquisition states returned by remote /acquire/{requestId} endpoint.
 */
public enum RemoteAcquireState {
    QUEUED,
    ACQUIRED,
    SKIPPED,
    FAILED,
    CANCELLED,
    EXPIRED,
    UNKNOWN;

    public static RemoteAcquireState fromString(String state) {
        if (state == null || state.isEmpty()) {
            return UNKNOWN;
        }
        try {
            return valueOf(state.trim().toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
