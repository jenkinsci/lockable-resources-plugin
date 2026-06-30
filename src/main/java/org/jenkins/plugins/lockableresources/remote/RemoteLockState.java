/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

/**
 * Server-side state of a remote lock record.
 * Distinct from the client-facing {@link RemoteAcquireState}.
 */
public enum RemoteLockState {
    /** Waiting to acquire the resource (resource is currently busy). */
    QUEUED,
    /** Resource is locked; client must send periodic heartbeats. */
    ACQUIRED,
    /** Resource was already locked and skipIfLocked=true was requested. */
    SKIPPED,
    /** Acquire failed (e.g. resource not found). */
    FAILED,
    /** Heartbeat timed out; resource is held pending admin review. */
    STALE
}
