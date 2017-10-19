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

import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;

import edu.umd.cs.findbugs.annotations.Nullable;

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
	private LockableResourcesStruct lockableResourcesStruct;
	
	/*
	 * Description of the required resources used within logging messages.
	 */
	private String resourceDescription;

	/*
	 * Priority this context should have in the queue. Lowest priority being 0.
	 */
	private int lockPriority = 0;

	/*
	 * Constructor for the QueuedContextStruct class.
	 */
	public QueuedContextStruct(StepContext context, LockableResourcesStruct lockableResourcesStruct, String resourceDescription) {
		this.context = context;
		this.lockableResourcesStruct = lockableResourcesStruct;
		this.resourceDescription = resourceDescription;
	}

	/*
	 * Constructor for the QueuedContextStruct class.
	 */
	public QueuedContextStruct(StepContext context, LockableResourcesStruct lockableResourcesStruct, String resourceDescription, int lockPriority) {
		this.context = context;
		this.lockableResourcesStruct = lockableResourcesStruct;
		this.resourceDescription = resourceDescription;
		this.lockPriority = lockPriority;
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
	public LockableResourcesStruct getResources() {
		return this.lockableResourcesStruct;
	}

	/*
	 * Gets the resource description for logging messages.
	 */
	public String getResourceDescription() {
		return this.resourceDescription;
	}

	public int getLockPriority() {
		return this.lockPriority;
	}

	private static final long serialVersionUID = 1L;
}
