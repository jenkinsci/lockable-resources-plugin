/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.queue;

/**
 * Exception thrown when a lock step times out waiting for resource allocation.
 */
public class LockWaitTimeoutException extends Exception {

    private static final long serialVersionUID = 1L;

    public LockWaitTimeoutException(String message) {
        super(message);
    }
}
