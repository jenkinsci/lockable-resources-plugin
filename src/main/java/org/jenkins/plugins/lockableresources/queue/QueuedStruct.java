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
import hudson.model.Queue;
import hudson.model.Run;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import java.io.Serializable;

/* 
 * This class is used to queue pipeline contexts 
 * which shall be executed once the necessary
 * resources are free'd.
 */
@ExportedBean(defaultVisibility = 999)
public abstract class QueuedStruct implements Serializable {

	/*
	 * Reference to the resources required by the step context.
	 */
	protected LockableResourcesStruct lockableResourcesStruct;

	/*
	 * Description of the required resources used within logging messages.
	 */
	protected String resourceDescription;

	/*
	 * Priority this context should have in the queue. Lowest priority being 0.
	 */
	protected int lockPriority = 0;

	/*
	 * Name of the environment variable holding the resource name
	 */
	protected String resourceVariableName;

	public abstract Object getIdentifier();

	@Exported
	public abstract String getBuildName();

	@Exported
	public abstract String getBuildUrl();

	/*
	 * Call this to check if the queued build that's waiting for resources hasn't gone away
	 */
	public abstract boolean isBuildStatusGood();

	/*
	 * Gets the required resources.
	 */
	public LockableResourcesStruct getResources() {
		return this.lockableResourcesStruct;
	}

	/*
	 * Gets the resource description for logging messages.
	 */
	@Exported
	public String getResourceDescription() {
		return this.resourceDescription;
	}

	public int getLockPriority() {
		return this.lockPriority;
	}

	public String getResourceVariableName() {
		return resourceVariableName;
	}

	private static final long serialVersionUID = 1L;
}
