/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2016, Florian Hug. All rights reserved.             *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

package org.jenkins.plugins.lockableresources;

import hudson.init.InitMilestone;
import hudson.init.Initializer;

/**
 * This class migrates "active" queuedContexts from LockableResource to LockableResourcesManager
 *
 * @deprecated Migration code for field introduced in 1.8 (since 1.11)
 */
@Deprecated
public final class BackwardCompatibility {

  private BackwardCompatibility() {}

  @Initializer(after = InitMilestone.JOB_LOADED)
  public static void compatibilityMigration() {
    LockableResourcesManager.get().compatibilityMigration();
  }
}
