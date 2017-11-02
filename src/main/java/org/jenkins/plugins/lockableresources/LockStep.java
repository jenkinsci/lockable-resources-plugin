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

	public int lockPriority = 0;

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
	public void setLockPriority(int lockPriority) {
		this.lockPriority = lockPriority;
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

	public static String toLogString(String resource,
                                     String label,
                                     int quantity,
                                     String variable,
                                     boolean inversePrecedence,
                                     int lockPriority) {
	    StringBuilder instanceString = new StringBuilder();
        // a label takes always priority over resource if both specified
        if (label != null && !label.isEmpty()) {
            instanceString.append("Label: ").append(label);
            instanceString.append(", Quantity: ");
            if (quantity > 0) {
                instanceString.append(quantity);
            } else {
                instanceString.append("All");
            }
        } else if (resource != null) {
            instanceString.append(resource);
        } else {
            return "[no resource or label specified - probably a bug]";
        }
        if (lockPriority > 0) {
            instanceString.append(", LockPriority: ").append(lockPriority);
        }
        if (variable != null && !variable.isEmpty()) {
            instanceString.append(", Variable: ").append(variable);
        }
        if (inversePrecedence) {
            instanceString.append(", InversePrecedence: ").append(inversePrecedence);
        }
        return instanceString.toString();
    }

	public String toString() {
	    return toLogString(
	            this.resource,
                this.label,
                this.quantity,
                this.variable,
                this.inversePrecedence,
                this.lockPriority);
	}

	/**
	 * Label and resource are mutually exclusive.
	 * LockPriority must be positive
	 */
	public void validate() throws Exception {
		if (label != null && !label.isEmpty() && resource !=  null && !resource.isEmpty()) {
			throw new IllegalArgumentException("Label and resource name cannot be specified simultaneously.");
		}
		if (this.lockPriority < 0) {
			throw new IllegalArgumentException("LockPriority must be 0 or positive");
		}
	}

	private static final long serialVersionUID = 1L;

}
