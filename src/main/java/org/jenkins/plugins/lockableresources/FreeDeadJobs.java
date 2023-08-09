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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkinsci.plugins.workflow.steps.StepContext;

/**
 * Sometimes after re-starts (jenkins crashed or what ever) are resources still
 * locked by build, but the build is no more running.
 * This script will 'unlock' all resource assigned to dead builds
 */
public final class FreeDeadJobs {
  
  private FreeDeadJobs() {}

  @Initializer(after = InitMilestone.JOB_LOADED)
  public static void freePostMortemResources() {
    LockableResourcesManager.get().freePostMortemResources();
  }
}
