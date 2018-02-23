/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2016, Florian Hug. All rights reserved.			 *
 *																	 *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.									*
 *																	 *
 * See the "LICENSE.txt" file for more information.					*
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import java.io.Serializable;

import com.infradna.tool.bridge_method_injector.WithBridgeMethods;
import hudson.model.AbstractBuild;
import hudson.model.Queue;
import hudson.model.Run;
import org.jenkinsci.plugins.workflow.steps.StepContext;

import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/* 
 * This class is used to queue pipeline contexts 
 * which shall be executed once the necessary
 * resources are free'd.
 */
@ExportedBean(defaultVisibility = 999)
public class QueuedContextStruct extends QueuedStruct implements Serializable {
	/*
	 * Reference to the pipeline step context.
	 */
	private StepContext context;

	// build information in the requesting context. Useful for displaying on the ui and logging
	private transient volatile Run<?, ?> build = null;
	private transient volatile String buildExternalizableId = null;

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
	@Override
	public Object getIdentifier() { return getContext(); }

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

	/*
	 * Call this to check if the queued build that's waiting for resources hasn't gone away
	 */
	public boolean isBuildStatusGood() {
		try {
			Run run = this.context.get(Run.class);
			return run != null;
		} catch (Exception e) {
			// Any exception means the run has gone bad
			// TODO Sue there are problems during a restart of jenkins where an exception
			// here might mean that things aren't fully initialised. Need to fix that case.
		}
		return false;
	}

	@Exported
	@Override
	public String getBuildName() {
		if (getBuild() != null)
			return getBuild().getFullDisplayName();
		else
			return null;
	}

	@Exported
	@Override
	public String getBuildUrl() {
		return getBuild().getUrl();
	}

	@Override
	public String toString() {
		return "Build(" + getBuildExternalizableId() + ") Resource(" + resourceDescription + ")";
	}
}
