/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                       *
 *                                                                           *
 * Resource reservation per node by Darius Mihai (mihai_darius22@yahoo.com)  *
 * Copyright (C) 2015 Freescale Semiconductor, Inc.                          *
 *                                                                           *
 * This file is part of the Jenkins Lockable Resources Plugin and is         *
 * published under the MIT license.                                          *
 *                                                                           *
 * See the "LICENSE.txt" file for more information.                          *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.AbstractProject;
import hudson.model.Node;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.model.queue.CauseOfBlockage;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.time.DateUtils;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

@Extension
public class LockableResourcesQueueTaskDispatcher extends QueueTaskDispatcher {

	private transient Cache<Long,Date> lastLogged = CacheBuilder.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();

	static final Logger LOGGER = Logger
			.getLogger(LockableResourcesQueueTaskDispatcher.class.getName());

	/**
	 * @param node The node that is checked if is able to run this queue item
	 * @param item The item that is verified if can be run on the given node
	 * @return A cause of blockage if the item cannot be built on the given
	 * node, or null if no errors occur
	 */
	@Override
	public CauseOfBlockage canTake(Node node, Queue.BuildableItem item) {
		/* Might no longer be required, but will leave it just to be sure */
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

		int resourceNumber;
		try {
			resourceNumber = Integer.parseInt(resources.requiredNumber);
		} catch (NumberFormatException e) {
			resourceNumber = 0;
		}

		LOGGER.finest(project.getName() +
			" trying to get resources with these details: " + resources);

		if (resourceNumber > 0 || !resources.label.isEmpty() || resources.getResourceMatchScript() != null) {
			Map<String, Object> params = new HashMap<String, Object>();
			if (item.task instanceof MatrixConfiguration) {
			    MatrixConfiguration matrix = (MatrixConfiguration) item.task;
			    params.putAll(matrix.getCombination());
			}
                        
			final List<LockableResource> selected ;
			try {
				selected = LockableResourcesManager.get().tryQueue(
					resources,
					item.getId(),
					project.getFullName(),
					resourceNumber,
					params,
					LOGGER,
					node);
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
				/* just to be sure, double check if all the resources in the list can be
				 * used by the given node. If any of the resources cannot
				 * be used, return a 'BecauseWrongNode' type causeOfBlockage
				 */
				for (LockableResource r : selected) {
					if (!r.isReservedForNode(node)) {
						LOGGER.finest(project.getName() + " required some resources"
								+ " that were not reserved for the current node");
						return new BecauseWrongNode();
					}
				}

				/* enqueue the resources for the itemId and project */
				LockableResourcesManager.get().queue(selected, project.getFullName(), item.getId(), node);

				LOGGER.finest(project.getName() + " reserved resources " + selected);
				return null;
			} else {
				LOGGER.finest(project.getName() + " waiting for resources");
				return new BecauseResourcesLocked(resources);
			}

		} else {
			if (LockableResourcesManager.get().queue(resources.required, project.getFullName(), item.getId(), node)) {
				LOGGER.finest(project.getName() + " reserved resources " + resources.required);
				return null;
			} else {
				LOGGER.finest(project.getName() + " waiting for resources " + resources.required);
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
			if (this.rscStruct.label.isEmpty())
				return "Waiting for resources " + rscStruct.required.toString();
			else
				return "Waiting for resources with label " + rscStruct.label;
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

	public static class BecauseWrongNode extends CauseOfBlockage {

		@Override
		public String getShortDescription() {
			return "Attempted to run on a wrong node";
		}
	}
}
