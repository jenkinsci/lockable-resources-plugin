/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.ParametersAction;
import hudson.model.ParameterValue;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

@Extension
public class LockableResourcesQueueTaskDispatcher extends QueueTaskDispatcher {

	private transient Cache<String,Date> lastLogged = CacheBuilder.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();

	static final Logger LOGGER = Logger
			.getLogger(LockableResourcesQueueTaskDispatcher.class.getName());

	@Override
	public CauseOfBlockage canRun(Queue.Item item) {
		// Skip locking for multiple configuration projects,
		// only the child jobs will actually lock resources.
		if (item.task instanceof MatrixProject)
			return null;

		Job<?, ?> project = Utils.getProject(item);
		if (project == null)
			return null;

		LockableResourcesStruct resources = Utils.requiredResources(project);
		if (resources == null ||
			(resources.required.isEmpty() && resources.label.isEmpty() && resources.getResourceMatchScript() == null)) {
			return null;
		}

		Map<String, Object> params = new HashMap<>();
		if (!resources.label.isEmpty() || resources.getResourceMatchScript() != null) {
			params = getBuildParams(project);
		}

		try {
			final List<LockableResource> selected = LockableResourcesManager.get().freeStyleLockOrQueue(
					resources,
					item.getId(),
					project,
					params,
					LOGGER);
			if (selected != null && !selected.isEmpty()) {
				LOGGER.info(project.getName() + " reserved resources " + selected);
				return null;
			} else {
				return new BecauseResourcesLocked(resources);
			}
		} catch (ExecutionException ex) {
			Throwable toReport = ex.getCause();
			if (toReport == null) { // We care about the cause only
				toReport = ex;
			}
			if (LOGGER.isLoggable(Level.WARNING)) {
				if (lastLogged.getIfPresent(project.getName()) == null) {
					lastLogged.put(project.getName(), new Date());

					String itemName = project.getFullName() + " (id=" + item.getId() + ")";
					LOGGER.log(Level.WARNING, "Failed to queue item " + itemName, toReport.getMessage());
				}
			}
			return new BecauseResourcesQueueFailed(resources, toReport);
		}

	}

	private Map<String, Object> getBuildParams(Job<?, ?> project) {
		Map<String, Object> params = new HashMap<String, Object>();
		// Inject Build Parameters, if possible and applicable to the "item" type
		try {
			List<ParametersAction> itemparams = project.getActions(ParametersAction.class);
			if (itemparams != null) {
				for (ParametersAction actparam : itemparams) {
					if (actparam == null) continue;
					for (ParameterValue p : actparam.getParameters()) {
						if (p == null) continue;
						params.put(p.getName(), p.getValue());
					}
				}
			}
		} catch (Exception ex) {
			// Report the error and go on with the build -
			// perhaps this item is not a build with args, etc.
			// Note this is likely to fail a bit later in such case.
			if (LOGGER.isLoggable(Level.WARNING)) {
				if (lastLogged.getIfPresent(project.getName()) == null) {
					lastLogged.put(project.getName(), new Date());
					String itemName = project.getFullName();
					LOGGER.log(Level.WARNING, "Failed to get build params from item " + itemName, ex);
				}
			}
		}

		if (project instanceof MatrixConfiguration) {
			MatrixConfiguration matrix = (MatrixConfiguration) project;
			params.putAll(matrix.getCombination());
		}
		return params;
	}

	public static class BecauseResourcesLocked extends CauseOfBlockage {

		private final LockableResourcesStruct rscStruct;

		public BecauseResourcesLocked(LockableResourcesStruct r) {
			this.rscStruct = r;
		}

		@Override
		public String getShortDescription() {
			return rscStruct.toLogString();
		}
	}
        
	// Only for UI
	@Restricted(NoExternalUse.class)
	public static class BecauseResourcesQueueFailed extends CauseOfBlockage {
		
		@NonNull
		private final LockableResourcesStruct resources;
		@NonNull
		private final Throwable cause;
		
		public BecauseResourcesQueueFailed(@NonNull LockableResourcesStruct resources, @NonNull Throwable cause) {
			this.cause = cause;
			this.resources = resources;
		}

		@Override
		public String getShortDescription() {
			return "Execution failed while acquiring the resource " + resources.toLogString() + ". " + cause.getMessage();
		}
	}
}
