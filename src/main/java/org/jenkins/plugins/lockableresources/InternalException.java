package org.jenkins.plugins.lockableresources;

public class InternalException extends Exception {
  public InternalException(String message) {
    super("An internal exception occurred in the LockableResourcesPlugin: "  + message);
  }
}
