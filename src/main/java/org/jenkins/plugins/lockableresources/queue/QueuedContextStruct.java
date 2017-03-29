/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2016, Florian Hug. All rights reserved.			 *
 *																	 *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.									*
 *																	 *
 * See the "LICENSE.txt" file for more information.					*
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import java.io.IOException;
import java.io.Serializable;

import com.infradna.tool.bridge_method_injector.WithBridgeMethods;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import org.jenkinsci.plugins.workflow.steps.StepContext;

import edu.umd.cs.findbugs.annotations.Nullable;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/* 
 * This class is used to queue pipeline contexts 
 * which shall be executed once the necessary
 * resources are free'd.
 */
@ExportedBean(defaultVisibility = 999)
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

	// build information in the requesting context. Useful for displaying on the ui and logging
	private transient volatile Run<?, ?> build = null;
	private transient volatile String buildExternalizableId = null;


	/* 
	 * Name of the environment variable holding the resource name
	 */
	private String resourceVariableName;

	/*
	 * Constructor for the QueuedContextStruct class.
	 */
	public QueuedContextStruct(StepContext context, LockableResourcesStruct lockableResourcesStruct, String resourceDescription, String resourceVariableName, int lockPriority) {
		this.context = context;
		this.lockableResourcesStruct = lockableResourcesStruct;
		this.resourceDescription = resourceDescription;
		this.resourceVariableName = resourceVariableName;
		this.lockPriority = lockPriority;
	}

	/*
	 * Gets the pipeline step context.
	 */
	public StepContext getContext() {
		return this.context;
	}

	@Exported
	public String getBuildExternalizableId() {
		if (this.buildExternalizableId == null) {
			// getting the externalizableId can fail for many reasons, set to null if it fails for some reason
			try {
				buildExternalizableId = this.context.get(Run.class).getExternalizableId();
			} catch (Exception e) {
				buildExternalizableId = null;
			}
		}
		return this.buildExternalizableId;
	}

	@WithBridgeMethods(value=AbstractBuild.class, adapterMethod="getAbstractBuild")
	public Run<?, ?> getBuild() {
		if (build == null) {
			build = Run.fromExternalizableId(getBuildExternalizableId());
		}
		return build;
	}

	@Exported
	public String getBuildName() {
		if (getBuild() != null)
			return getBuild().getFullDisplayName();
		else
			return null;
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

	@Override
	public String toString() {
		return "Build(" + getBuildExternalizableId() + ") Resource(" + resourceDescription + ")";
	}
}
