package org.jenkins.plugins.lockableresources;

import java.io.Serializable;

import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import hudson.Extension;
import hudson.model.AutoCompletionCandidates;

public class LockStep extends AbstractStepImpl implements Serializable {

	public final String resource;

	public Integer maxWaiting;

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

	@DataBoundSetter
	public void setMaxWaiting(Integer maxWaiting) {
		this.maxWaiting = maxWaiting;
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
			return "Lock shared resources to manage concurrency";
		}

		@Override
		public boolean takesImplicitBlockArgument() {
			return true;
		}

		public AutoCompletionCandidates doAutoCompleteResource(@QueryParameter String value) {
			return RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames(value);
		}
	}

	private static final long serialVersionUID = 1L;

}
