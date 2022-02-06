/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2016, Florian Hug. All rights reserved.             *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import java.io.Serializable;
import java.util.List;
import org.jenkinsci.plugins.workflow.steps.StepContext;

/*
 * This class is used to queue pipeline contexts
 * which shall be executed once the necessary
 * resources are free'd.
 */
public class QueuedContextStruct implements Serializable {

  /*
   * Reference to the pipeline step context.
   */
  private StepContext context;

  /*
   * Reference to the resources required by the step context.
   */
  private List<LockableResourcesStruct> lockableResourcesStruct;

  /*
   * Description of the required resources used within logging messages.
   */
  private String resourceDescription;

  /*
   * Name of the variable to save the locks taken.
   */
  private String variableName;

  /*
   * Constructor for the QueuedContextStruct class.
   */
  public QueuedContextStruct(StepContext context, List<LockableResourcesStruct> lockableResourcesStruct, String resourceDescription, String variableName) {
    this.context = context;
    this.lockableResourcesStruct = lockableResourcesStruct;
    this.resourceDescription = resourceDescription;
    this.variableName = variableName;
  }

  /*
   * Gets the pipeline step context.
   */
  public StepContext getContext() {
    return this.context;
  }

  /*
   * Gets the required resources.
   */
  public List<LockableResourcesStruct> getResources() {
    return this.lockableResourcesStruct;
  }

  /*
   * Gets the resource description for logging messages.
   */
  public String getResourceDescription() {
    return this.resourceDescription;
  }

  /*
   * Gets the variable name to save the locks taken.
   */
  public String getVariableName() {
    return this.variableName;
  }

  private static final long serialVersionUID = 1L;
}
