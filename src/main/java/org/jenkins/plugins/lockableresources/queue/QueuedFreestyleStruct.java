/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2016, Florian Hug. All rights reserved.			 *
 *																	 *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.									*
 *																	 *
 * See the "LICENSE.txt" file for more information.					*
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import com.infradna.tool.bridge_method_injector.WithBridgeMethods;
import hudson.model.AbstractBuild;
import hudson.model.Job;
import hudson.model.Project;
import hudson.model.Queue;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import java.io.Serializable;
import java.util.logging.Level;

/*
 * This class is used to queue Freestyle projects
 * which shall be executed once the necessary
 * resources are free'd.
 */
@ExportedBean(defaultVisibility = 999)
public class QueuedFreestyleStruct extends QueuedStruct implements Serializable {

  private Long freestyleQueueId;
  private String freestyleQueueProjectName;
  private String freestyleQueueProjectUrl;

  public QueuedFreestyleStruct(Long freestyleQueueId,
                               Job freestyleQueueProject,
                               LockableResourcesStruct lockableResourcesStruct,
                               String resourceDescription,
                               String resourceVariableName,
                               int lockPriority) {
    this.freestyleQueueId = freestyleQueueId;
    this.freestyleQueueProjectName = freestyleQueueProject.getFullName();
    this.freestyleQueueProjectUrl = freestyleQueueProject.getUrl();
    this.lockableResourcesStruct = lockableResourcesStruct;
    this.resourceDescription = resourceDescription;
    this.resourceVariableName = resourceVariableName;
    this.lockPriority = lockPriority;
  }

  @Exported
  @Override
  public String getBuildName() {
    return this.freestyleQueueProjectName;
  }

  @Exported
  @Override
  public String getBuildUrl() {
    return this.freestyleQueueProjectUrl;
  }

  @Exported
  public String getBuild() {
    return getBuildName();
  }

  /*
   * Call this to check if the queued build that's waiting for resources hasn't gone away
   */
  public boolean isBuildStatusGood() {
    try {
      Queue.Item item = Queue.getInstance().getItem(this.freestyleQueueId);
      return item != null;
    } catch (Exception e) {
      // Any exception means the run has gone bad
    }
    return false;
  }

  public Long getFreestyleQueueId() {
    return freestyleQueueId;
  }

  @Override
  public Object getIdentifier() {
    return freestyleQueueId;
  }

  @Override
  public String toString() {
    return "Freestyle(" + this.getBuildName() + this.getBuildUrl() + ") Id(" + getIdentifier() + ")" +
        "Resource(" + resourceDescription + ")";
  }
}
