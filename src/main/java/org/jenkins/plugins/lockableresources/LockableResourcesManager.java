/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkinsci.plugins.workflow.steps.StepContext;

import hudson.Extension;
import hudson.model.Run;

import javax.annotation.Nullable;

@Extension
public class LockableResourcesManager extends GlobalConfiguration {

	private static final Logger LOGGER = Logger.getLogger(LockableResourcesManager.class.getName());

	private List<LockableResource> resources;

	public LockableResourcesManager() {
		resources = new ArrayList<>();

		load();
	}

	public List<LockableResource> getResources() {
		return resources;
	}

	public List<LockableResource> getResourcesFromProject(String fullName, String label, Map<String, Object> params) {
		List<LockableResource> matching = new ArrayList<>();

		for (LockableResource resource : resources) {
			String queueItemProject = resource.getQueueItemProject();

			if (queueItemProject != null && queueItemProject.equals(fullName)) {
				if (resource.isValidLabel(label, params)) {
					matching.add(resource);
				}
			}
		}

		return matching;
	}

	public List<LockableResource> getResourcesFromBuild(Run<?, ?> build) {
		List<LockableResource> matching = new ArrayList<>();

		for (LockableResource resource : resources) {
			Run<?, ?> resourceBuild = resource.getBuild();

			if (resourceBuild != null && resourceBuild == build) {
				matching.add(resource);
			}
		}

		return matching;
	}

	public Boolean isValidLabel(String label) {
		return label.startsWith(LockableResource.GROOVY_LABEL_MARKER)
						|| this.getAllLabels().contains(label);
	}

	public Set<String> getAllLabels() {
		Set<String> labels = new HashSet<>();
		for (LockableResource resource : this.resources) {
			String rl = resource.getLabels();
			if (rl == null || "".equals(rl))
				continue;
			labels.addAll(Arrays.asList(rl.split("\\s+")));
		}
		return labels;
	}

	public int getFreeResourceAmount(String label) {
		int free = 0;
		for (LockableResource r : this.resources) {
			if (r.isLocked() || r.isQueued() || r.isReserved())
				continue;
			if (Arrays.asList(r.getLabels().split("\\s+")).contains(label))
				free += 1;
		}
		return free;
	}

	public List<LockableResource> getResourcesWithNames(List<String> names) {
		List<LockableResource> found = new ArrayList<>();

		for (String name : names) {
			LockableResource resource = fromName(name);

			if (resource != null) {
				found.add(resource);
			}
		}

		return found;
	}

	public List<LockableResource> getResourcesWithLabel(String label, Map<String, Object> params) {
		List<LockableResource> found = new ArrayList<>();

		for (LockableResource resource : resources) {
			if (resource.isValidLabel(label, params)) {
				found.add(resource);
			}
		}

		return found;
	}

	public LockableResource fromName(String resourceName) {
		if (resourceName != null) {
			for (LockableResource resource : resources) {
				if (resourceName.equals(resource.getName())) {
					return resource;
				}
			}
		}

		return null;
	}

	public synchronized List<LockableResource> queue(LockableResourcesStruct requiredResources,
																									 long queueItemId,
																									 String queueItemProject,
																									 Map<String, Object> params) {
		List<LockableResource> selected = new ArrayList<>();
		List<LockableResource> candidates = getCandidates(requiredResources, params);

		for (LockableResource resource : candidates) {
			if (resource.isReserved() || resource.isQueued(queueItemId) || resource.isLocked()) {
				return null;
			}

			resource.setQueued(queueItemId, queueItemProject);
		}

		return selected;
	}

	public synchronized List<LockableResource> queue(LockableResourcesStruct requiredResources,
																									 long queueItemId,
																									 String queueItemProject,
																									 int number,  // 0 means all
																									 Map<String, Object> params) {
		List<LockableResource> selected = new ArrayList<>();
		List<LockableResource> candidates = getCandidates(requiredResources, params);

		if (!checkCurrentResourcesStatus(selected, candidates, queueItemProject, queueItemId)) {
			// The project has another buildable item waiting -> bail out
			LOGGER.log(Level.FINEST, "{0} has another build waiting resources." +
											" Waiting for it to proceed first.",
							new Object[]{queueItemProject});
			return null;
		}

		for (LockableResource rs : candidates) {
			if (number != 0 && (selected.size() >= number)) {
				break;
			}
			if (!rs.isReserved() && !rs.isLocked() && !rs.isQueued()) {
				selected.add(rs);
			}
		}

		// if did not get wanted amount or did not get all
		int required_amount = number == 0 ? candidates.size() : number;

		if (selected.size() != required_amount) {
			LOGGER.log(Level.FINEST, "{0} found {1} resource(s) to queue." +
											"Waiting for correct amount: {2}.",
							new Object[]{queueItemProject, selected.size(), required_amount});

			// just to be sure, clean up
			for (LockableResource resource : resources) {
				if (resource.getQueueItemProject() != null && resource.getQueueItemProject().equals(queueItemProject)) {
					resource.unqueue();
				}
			}

			return null;
		}

		for (LockableResource resource : selected) {
			resource.setQueued(queueItemId, queueItemProject);
		}

		return selected;
	}

	private List<LockableResource> getCandidates(LockableResourcesStruct requiredResources, Map<String, Object> params) {
		List<LockableResource> candidates;

		if (requiredResources.label != null && requiredResources.label.isEmpty()) {
			candidates = getResourcesWithNames(requiredResources.resourceNames);
		} else {
			candidates = getResourcesWithLabel(requiredResources.label, params);
		}

		return candidates;
	}

	// Adds already selected (in previous queue round) resources to 'selected'
	// Return false if another item queued for this project -> bail out
	private boolean checkCurrentResourcesStatus(List<LockableResource> selected,
																							List<LockableResource> candidates,
																							String project,
																							long taskId) {
		for (LockableResource resource : candidates) {
			// This project might already have something in queue
			String rProject = resource.getQueueItemProject();
			if (rProject != null && rProject.equals(project)) {
				if (resource.isQueuedByTask(taskId)) {
					// this item has queued the resource earlier
					selected.add(resource);
				} else {
					// The project has another buildable item waiting -> bail out
					LOGGER.log(Level.FINEST, "{0} has another build " +
													"that already queued resource {1}. Continue queueing.",
									new Object[]{project, resource});
					return false;
				}
			}
		}
		return true;
	}

	public synchronized boolean lock(List<LockableResource> resources, Run<?, ?> build, @Nullable StepContext context) {
		return lock(resources, build, context, false);
	}

	/**
	 * Try to lock the resource and return true if locked.
	 */
	public synchronized boolean lock(List<LockableResource> resources,
																	 Run<?, ?> build, @Nullable StepContext context, boolean inversePrecedence) {
		boolean needToWait = false;

		for (LockableResource r : resources) {
			if (r.isReserved() || r.isLocked()) {
				needToWait = true;

				if (context != null) {
					r.queueAdd(context);

					break;
				}
			}
		}

		if (!needToWait) {
			for (LockableResource r : resources) {
				r.unqueue();
				r.setBuild(build);

				if (context != null) {
					LockStepExecution.proceed(context, r.getName(), inversePrecedence);

					break;
				}
			}
		}

		save();

		return !needToWait;
	}

	public synchronized void unlock(List<LockableResource> resourcesToUnLock,
																	Run<?, ?> build, @Nullable StepContext context) {
		unlock(resourcesToUnLock, build, context, false);
	}

	public synchronized void unlock(List<LockableResource> resourcesToUnLock,
																	Run<?, ?> build, @Nullable StepContext context, boolean inversePrecedence) {
		for (LockableResource r : resourcesToUnLock) {
			// Search the resource in the internal list to unlock it
			for (LockableResource internal : resources) {
				if (internal.getName().equals(r.getName())) {
					if (build == null ||
									(internal.getBuild() != null && build.getExternalizableId().equals(internal.getBuild().getExternalizableId()))) {
						// this will remove the context from the queue and setBuild(null) if there are no more contexts
						StepContext nextContext = internal.getNextQueuedContext(inversePrecedence);
						if (nextContext != null) {
							try {
								internal.setBuild(nextContext.get(Run.class));
							} catch (Exception e) {
								throw new IllegalStateException("Can not access the context of a running build", e);
							}
							LockStepExecution.proceed(nextContext, internal.getName(), inversePrecedence);
						} else {
							// No more contexts, unlock resource
							internal.unqueue();
							internal.setBuild(null);
						}
					}
				}
			}
		}
		save();
	}

	/**
	 * Creates the resource if it does not exist.
	 */
	public synchronized boolean createResource(String name) {
		LockableResource existent = fromName(name);

		if (existent == null) {
			getResources().add(new LockableResource(name));
			save();

			return true;
		}

		return false;
	}

	public synchronized boolean cleanWaitingContext(LockableResource resource, StepContext context) {
		for (LockableResource r : resources) {
			if (r.equals(resource)) {
				return r.remove(context);
			}
		}

		return false;
	}

	public synchronized boolean reserve(List<LockableResource> resources, String userName) {
		for (LockableResource resource : resources) {
			if (resource.isReserved() || resource.isLocked() || resource.isQueued()) {
				return false;
			}
		}

		for (LockableResource resource : resources) {
			resource.setReservedBy(userName);
		}

		save();

		return true;
	}

	public synchronized void unreserve(List<LockableResource> resources) {
		for (LockableResource resource : resources) {
			resource.unReserve();
		}

		save();
	}

	@Override
	public String getDisplayName() {
		return "External Resources";
	}

	public synchronized void reset(List<LockableResource> resources) {
		for (LockableResource resource : resources) {
			resource.reset();
		}

		save();
	}

	@Override
	public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
		try {
			List<LockableResource> resources = req.bindJSONToList(LockableResource.class, json.get("resources"));

			for (LockableResource resource : resources) {
				LockableResource oldResource = fromName(resource.getName());

				if (oldResource != null) {
					resource.setBuild(oldResource.getBuild());
					resource.setQueued(resource.getQueueItemId(), resource.getQueueItemProject());
				}
			}

			this.resources = resources;

			save();

			return true;
		} catch (JSONException e) {
			return false;
		}
	}

	public static LockableResourcesManager get() {
		Jenkins jenkins = Jenkins.getInstance();

		if (jenkins != null) {
			return (LockableResourcesManager) jenkins.getDescriptorOrDie(LockableResourcesManager.class);
		}

		throw new IllegalStateException("Jenkins instance has not been started or was already shut down.");
	}

}
