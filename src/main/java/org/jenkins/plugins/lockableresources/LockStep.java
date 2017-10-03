package org.jenkins.plugins.lockableresources;

import java.io.Serializable;

import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import hudson.Extension;
import hudson.model.AutoCompletionCandidates;
import hudson.util.FormValidation;
import hudson.Util;

import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.CheckForNull;

public class LockStep extends AbstractStepImpl implements Serializable {

	@CheckForNull
	public String resource = null;

	@CheckForNull
	public String label = null;

	@CheckForNull
	public String variable = null;

	public int quantity = 0;

	public boolean inversePrecedence = false;

	// it should be LockStep() - without params. But keeping this for backward compatibility
	// so `lock('resource1')` still works and `lock(label: 'label1', quantity: 3)` works too (resource is not required)
	@DataBoundConstructor
	public LockStep(String resource) {
		if (resource != null && !resource.isEmpty()) {
			this.resource = resource;
		}
	}

	@DataBoundSetter
	public void setInversePrecedence(boolean inversePrecedence) {
		this.inversePrecedence = inversePrecedence;
	}

	@DataBoundSetter
	public void setLabel(String label) {
		if (label != null && !label.isEmpty()) {
			this.label = label;
		}
	}

	@DataBoundSetter
	public void setQuantity(int quantity) {
		this.quantity = quantity;
	}

	@DataBoundSetter
	public void setVariable(String variable) {
		this.variable = variable;
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

		public static FormValidation doCheckLabel(@QueryParameter String value, @QueryParameter String resource) {
			String resourceLabel = Util.fixEmpty(value);
			String resourceName = Util.fixEmpty(resource);
			if (resourceLabel != null && resourceName != null) {
				return FormValidation.error("Label and resource name cannot be specified simultaneously.");
			}
			if ((resourceLabel == null) && (resourceName == null)) {
				return FormValidation.error("Either label or resource name must be specified.");
			}
			return FormValidation.ok();
		}

		public static FormValidation doCheckResource(@QueryParameter String value, @QueryParameter String label) {
			return doCheckLabel(label, value);
		}
	}

	public String toString() {

		StringBuilder result = new StringBuilder();

		// a label takes always priority
		if (this.label != null) {
			result.append("Label: ").append(this.label);

			if (this.quantity > 0) {
				result.append(", Quantity: ").append(this.quantity);
			}

			if (variable != null) {
				result.append(", Variable: ").append(this.variable);
			}
			return result.toString();
		}
		// make sure there is an actual resource specified
		if (this.resource != null) {
			result.append(this.resource);
		} else {
			return "[no resource/label specified - probably a bug]";
		}
		if (variable != null) {
			result.append(", Variable: ").append(this.variable);
		}

		return result.toString();
	}

	/**
	* Label and resource are mutual exclusive.
	*/
	public void validate() throws Exception {
		if (label != null && !label.isEmpty() && resource !=  null && !resource.isEmpty()) {
			throw new IllegalArgumentException("Label and resource name cannot be specified simultaneously.");
		}
	}

	private static final long serialVersionUID = 1L;

}
