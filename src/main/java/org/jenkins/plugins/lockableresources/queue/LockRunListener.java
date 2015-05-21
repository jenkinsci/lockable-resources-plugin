/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013-2015, 6WIND S.A.                                 *
 *                          SAP SE                                     *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Environment;
import hudson.model.listeners.RunListener;
import hudson.model.ParametersAction;
import hudson.model.ParameterValue;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import java.io.IOException;
import java.util.ArrayList;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;

@Extension
public class LockRunListener extends RunListener<AbstractBuild<?, ?>> {

	static final String LOG_PREFIX = "[lockable-resources]";
	static final Logger LOGGER = Logger.getLogger(LockRunListener.class
			.getName());

	@Override
	public void onStarted(AbstractBuild<?, ?> build, TaskListener listener) {
		AbstractProject<?, ?> proj = Utils.getProject(build);
		List<LockableResource> required;
		if (proj != null) {
			LockableResourcesStruct resources = Utils.requiredResources(proj);
			if (resources != null) {
				if (resources.requiredNumber != null || !resources.label.isEmpty()) {
					required = LockableResourcesManager.get().
						getResourcesFromProject(proj.getFullName());
				} else {
					required = resources.required;
				}
				if (LockableResourcesManager.get().lock(required, build)) {
					build.addAction(LockedResourcesBuildAction
							.fromResources(required));
					listener.getLogger().printf("%s acquired lock on %s\n",
							LOG_PREFIX, required);
					LOGGER.log(Level.FINE, "{0} acquired lock on {1}",
							new Object[]{build.getFullDisplayName(), required});
					if (resources.requiredVar != null) {
						List<ParameterValue> params = new ArrayList<ParameterValue>();
						params.add(new StringParameterValue(
							resources.requiredVar,
							required.toString().replaceAll("[\\]\\[]", "")));
						build.addAction(new ParametersAction(params));
					}
				} else {
					listener.getLogger().printf("%s failed to lock %s\n",
							LOG_PREFIX, required);
					LOGGER.log(Level.FINE, "{0} failed to lock {1}",
							new Object[]{build.getFullDisplayName(), required});
				}
			}
		}
	}

	@Override
	public Environment setUpEnvironment(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException, Run.RunnerAbortedException {
		EnvVars env = new EnvVars();
		AbstractProject<?, ?> proj = Utils.getProject(build);
		for ( LockableResource r : LockableResourcesManager.get().getResourcesFromBuild(build) ) {
			String envProps = r.getProperties();
			if ( envProps != null ) {
				for ( String prop : envProps.split("\\s*[\\r\\n]+\\s*") ) {
					env.addLine(prop);
				}
			}
		}
		return Environment.create(env);
	}

	@Override
	public void onCompleted(AbstractBuild<?, ?> build, TaskListener listener) {
		// obviously project name cannot be obtained here
		List<LockableResource> required = LockableResourcesManager.get()
				.getResourcesFromBuild(build);
		if (required.size() > 0) {
			LockableResourcesManager.get().unlock(required, build);
			listener.getLogger().printf("%s released lock on %s\n",
					LOG_PREFIX, required);
			LOGGER.log(Level.FINE, "{0} released lock on {1}",
					new Object[]{build.getFullDisplayName(), required});
		}

	}

	@Override
	public void onDeleted(AbstractBuild<?, ?> build) {
		List<LockableResource> required = LockableResourcesManager.get()
				.getResourcesFromBuild(build);
		if (required.size() > 0) {
			LockableResourcesManager.get().unlock(required, build);
			LOGGER.log(Level.FINE, "{0} released lock on {1}",
					new Object[]{build.getFullDisplayName(), required});
		}
	}

}
