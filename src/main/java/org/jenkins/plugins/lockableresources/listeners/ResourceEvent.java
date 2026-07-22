/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.listeners;

/**
 * Describes a state change that happened to a {@link
 * org.jenkins.plugins.lockableresources.LockableResource}.
 */
public enum ResourceEvent {
    /** Resource was locked by a build. */
    LOCKED,
    /** Resource was unlocked (build finished or explicit unlock). */
    UNLOCKED,
    /** Resource was reserved by a user. */
    RESERVED,
    /** Resource was unreserved. */
    UNRESERVED,
    /** Resource was stolen from a build/user and reassigned. */
    STOLEN,
    /** Resource was reassigned to a different user. */
    REASSIGNED,
    /** Resource was reset (all state cleared). */
    RESET,
    /** Resource was recycled (unlocked + unreserved). */
    RECYCLED,
    /** Resource was queued for a build. */
    QUEUED
}
