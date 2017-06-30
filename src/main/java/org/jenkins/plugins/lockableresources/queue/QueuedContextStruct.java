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

/**
 * This class is used to queue pipeline contexts 
 * which shall be executed once the necessary
 * resources are free'd.
 */
public class QueuedContextStruct implements Serializable {

	/**
	 * Reference to the pipeline step context.
	 */
	private StepContext context;
	
	/**
	 * Reference to the resources required by the step context.
	 */
	private LockableResourcesStruct lockableResourcesStruct;
	
	/**
	 * Description of the required resources used within logging messages.
	 */
	private String resourceDescription;

	/**
	 * True if the relevant step is "getLock" rather than "lock"
	 */
	private boolean nonBlock = false;

	public QueuedContextStruct(StepContext context, LockableResourcesStruct lockableResourcesStruct, String resourceDescription) {
		this(context, lockableResourcesStruct, resourceDescription, false);
	}

	/**
	 * Constructor for the QueuedContextStruct class.
	 */
	public QueuedContextStruct(StepContext context, LockableResourcesStruct lockableResourcesStruct, String resourceDescription,
							   boolean nonBlock) {
		this.context = context;
		this.lockableResourcesStruct = lockableResourcesStruct;
		this.resourceDescription = resourceDescription;
		this.nonBlock = nonBlock;
	}
	
	/**
	 * Gets the pipeline step context.
	 */
	public StepContext getContext() {
		return this.context;
	}
	
	/**
	 * Gets the required resources.
	 */
	public LockableResourcesStruct getResources() {
		return this.lockableResourcesStruct;
	}

	/**
	 * Gets the resource description for logging messages.
	 */
	public String getResourceDescription() {
		return this.resourceDescription;
	}

	/**
	 * True if the relevant step is "getLock" (and therefore not block-scoped)
	 */
	public boolean isNonBlock() {
		return nonBlock;
	}

	private static final long serialVersionUID = 1L;
}
