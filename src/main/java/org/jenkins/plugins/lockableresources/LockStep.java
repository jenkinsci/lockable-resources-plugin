package org.jenkins.plugins.lockableresources;

import java.io.Serializable;

import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import hudson.Extension;
import hudson.model.AutoCompletionCandidates;

import edu.umd.cs.findbugs.annotations.Nullable;

public class LockStep extends AbstractStepImpl implements Serializable {

	public String resource = null;

	public String label = null;

	public int quantity = 0;

	public boolean inversePrecedence = false;

	@DataBoundConstructor
	public LockStep() {
	}

	@DataBoundSetter
	public void setInversePrecedence(boolean inversePrecedence) {
		this.inversePrecedence = inversePrecedence;
	}

	@DataBoundSetter
	public void setResource(@Nullable String resource) {
		this.resource = resource;
	}

	@DataBoundSetter
	public void setLabel(@Nullable String label) {
		if (label != null && !label.isEmpty())
		{
			this.label = label;
		}
	}

	@DataBoundSetter
	public void setQuantity(int quantity) {
		this.quantity = quantity;
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
			return RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames(value);
		}
	}

	@Override
	public String toString() {
		// a label takes always priority
		if (this.label != null)
		{
			if (this.quantity > 0)
			{
				return "Label: " + this.label + ", Quantity: " + this.quantity;
			}
			return "Label: " + this.label;
		}
		// make sure there is an actual resource specified
		if (this.resource != null)
		{
			return this.resource;
		}
		return "";
	}

	private static final long serialVersionUID = 1L;

}
