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
import hudson.EnvVars;
import hudson.Extension;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
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
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

@Extension
public class LockableResourcesQueueTaskDispatcher extends QueueTaskDispatcher {

	private transient Cache<Long,Date> lastLogged = CacheBuilder.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();

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

		EnvVars env = new EnvVars();
		for (ParametersAction pa : item.getActions(ParametersAction.class)) {
			for (ParameterValue p : pa.getParameters()) {
				try {
					String value = p.createVariableResolver(null).resolve(p.getName());
					if (value != null)
						env.put(p.getName(), value);
				}
				catch (Exception e) {
					LOGGER.log(Level.WARNING, "Unable to resolve parameter, " + p.getName(), e);
				}
			}
		}

		LockableResourcesStruct resources = Utils.requiredResources(project, env);

		if (resources == null ||
			(resources.required.isEmpty() && resources.label.isEmpty() && resources.getResourceMatchScript() == null)) {
			return null;
		}

		int resourceNumber = 0;
		if (resources.requiredNumber != null) {
			try {
				resourceNumber = Integer.parseInt(resources.requiredNumber);
			} catch (NumberFormatException e) {
				LOGGER.log(Level.WARNING, "Failed to convert the required number to an integer, " + resources.requiredNumber, e);
				resourceNumber = 0;
			}
		}

		LOGGER.finest(project.getName() +
			" trying to get resources with these details: " + resources);

		if (resourceNumber > 0 || !resources.label.isEmpty() || resources.getResourceMatchScript() != null) {
			Map<String, Object> params = null;

			if (resources.getResourceMatchScript() != null) {
				params = new HashMap<>();
				for (ParametersAction pa : item.getActions(ParametersAction.class)) {
					for (ParameterValue p : pa.getParameters()) {
						params.put(p.getName(), p.getValue());
					}
				}

				if (item.task instanceof MatrixConfiguration) {
					MatrixConfiguration matrix = (MatrixConfiguration) item.task;
					params.putAll(matrix.getCombination());
				}
			}

			final List<LockableResource> selected;
			try {
				selected = LockableResourcesManager.get().tryQueue(
					resources,
					item.getId(),
					project.getFullName(),
					resourceNumber,
					params,
					LOGGER);
			} catch(ExecutionException ex) {
				Throwable toReport = ex.getCause();
				if (toReport == null) { // We care about the cause only
					toReport = ex;
				}
				if (LOGGER.isLoggable(Level.WARNING)) {
					if (lastLogged.getIfPresent(item.getId()) == null) {
						lastLogged.put(item.getId(), new Date());

						String itemName = project.getFullName() + " (id=" + item.getId() + ")";
						LOGGER.log(Level.WARNING, "Failed to queue item " + itemName, toReport.getMessage());
					}
				}

				return new BecauseResourcesQueueFailed(resources, toReport);
			}

			if (selected != null) {
				LOGGER.finest(project.getName() + " reserved resources " + selected);
				return null;
			} else {
				LOGGER.finest(project.getName() + " waiting for resources");
				return new BecauseResourcesLocked(resources);
			}

		} else {
			if (LockableResourcesManager.get().queue(resources.required, item.getId(), project.getFullDisplayName())) {
				LOGGER.finest(project.getName() + " reserved resources " + resources.required);
				return null;
			} else {
				LOGGER.finest(project.getName() + " waiting for resources "
					+ resources.required);
				return new BecauseResourcesLocked(resources);
			}
		}
	}

	public static class BecauseResourcesLocked extends CauseOfBlockage {

		private final LockableResourcesStruct rscStruct;

		public BecauseResourcesLocked(LockableResourcesStruct r) {
			this.rscStruct = r;
		}

		@Override
		public String getShortDescription() {
			if (this.rscStruct.label.isEmpty()) {
				if (!this.rscStruct.required.isEmpty()) {
					return "Waiting for resource instances " + rscStruct.required.toString();
				} else {
					final SecureGroovyScript systemGroovyScript = this.rscStruct.getResourceMatchScript();
					if (systemGroovyScript != null) {
						// Empty or not... just keep the logic in sync
						// with tryQueue() in LockableResourcesManager
						if (systemGroovyScript.getScript().isEmpty()) {
							return "Waiting for resources identified by custom script (which is empty)";
						} else {
							return "Waiting for resources identified by custom script";
						}
					}
					// TODO: Developers should extend here if LockableResourcesStruct is extended
					LOGGER.log(Level.WARNING, "Failed to classify reason of waiting for resource: "
						+ this.rscStruct.toString());
					return "Waiting for lockable resources";
				}
			} else {
				return "Waiting for resources with label " + rscStruct.label;
			}
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
			//TODO: Just a copy-paste from BecauseResourcesLocked, seems strange
			String resourceInfo = (resources.label.isEmpty()) ? resources.required.toString() : "with label " + resources.label;
			return "Execution failed while acquiring the resource " + resourceInfo + ". " + cause.getMessage();
		}
	}
}
