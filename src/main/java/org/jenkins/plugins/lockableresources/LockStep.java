package org.jenkins.plugins.lockableresources;

import java.io.Serializable;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;

import hudson.Extension;
import hudson.model.AutoCompletionCandidates;

public class LockStep extends AbstractStepImpl implements Serializable {

	private static final long serialVersionUID = 1L;

	public final String resource;

	public boolean inversePrecedence = false;

	@DataBoundConstructor
	public LockStep(String resource) {
		if (resource == null || resource.isEmpty()) {
			throw new IllegalArgumentException("must specify resource");
		}

		this.resource = resource;
	}

	@DataBoundSetter
	public void setInversePrecedence(boolean inversePrecedence) {
		this.inversePrecedence = inversePrecedence;
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
			return "Lock shared resource";
		}

		@Override
		public boolean takesImplicitBlockArgument() {
			return true;
		}

		public AutoCompletionCandidates doAutoCompleteResource(@QueryParameter String value) {
			return RequiredResources.DescriptorImpl.doAutoCompleteResourceNames(value);
		}

	}

}
