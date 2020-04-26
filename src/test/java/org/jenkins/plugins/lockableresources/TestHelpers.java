/* SPDX-License-Identifier: MIT
 * Copyright (c) 2020, Tobias Gruetzmacher
 */
package org.jenkins.plugins.lockableresources;

import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import jenkins.model.Jenkins;

public final class TestHelpers {

  private static final int SLEEP_TIME = 100;
  private static final int MAX_WAIT = 5000;

  // Utility class
  private TestHelpers() {}

  public static void waitForQueue(Jenkins jenkins, FreeStyleProject job)
      throws InterruptedException {
    waitForQueue(jenkins, job, Queue.Item.class);
  }

  /** Schedule a build and make sure it has been added to Jenkins' queue. */
  public static void waitForQueue(Jenkins jenkins, FreeStyleProject job, Class<?> itemType)
      throws InterruptedException {
    System.out.print("Waiting for job to be queued...");
    int waitTime = 0;
    while (!itemType.isInstance(jenkins.getQueue().getItem(job)) && waitTime < MAX_WAIT) {
      Thread.sleep(SLEEP_TIME);
      waitTime += SLEEP_TIME;
      if (waitTime % 1000 == 0) {
        System.out.print(" " + waitTime / 1000 + "s");
      }
    }
    System.out.println();
  }
}
