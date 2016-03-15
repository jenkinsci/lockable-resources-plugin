package org.jenkins.plugins.lockableresources;

import java.io.Serializable;

import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;

public class LockStep extends AbstractStepImpl implements Serializable {

	public final String resource;

	@DataBoundConstructor
	public LockStep(String resource) {
		if (resource == null || resource.isEmpty()) {
			throw new IllegalArgumentException("must specify resource");
		}
		if (LockableResourcesManager.get().fromName(resource) == null) {
			throw new IllegalArgumentException("resource [" + resource + "] does not exist, missing global definition?");
		}
		this.resource = resource;
	}

	@Extension
	public static final class DescriptorImpl extends AbstractStepDescriptorImpl {

		public DescriptorImpl() {
			super(LockStepExecution.class);
		}

		@Override
		public String getFunctionName() {
			return "lock";
		}

		@Override
		public String getDisplayName() {
			return "Lock";
		}

		@Override
		public boolean takesImplicitBlockArgument() {
			return true;
		}
	}

	private static final long serialVersionUID = 1L;

}
