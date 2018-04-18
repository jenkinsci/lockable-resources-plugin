package org.jenkins.plugins.lockableresources;

import java.io.Serializable;

import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.Util;

import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.CheckForNull;

public class LockStepResource extends AbstractDescribableImpl<LockStepResource> implements Serializable {

	@CheckForNull
	public String resource = null;

	@CheckForNull
	public String label = null;

	public int quantity = 0;

	LockStepResource(String resource, String label, int quantity) {
		this.resource = resource;
		this.label = label;
		this.quantity = quantity;
	}

	@DataBoundConstructor
	public LockStepResource(String resource) {
		if (resource != null && !resource.isEmpty()) {
			this.resource = resource;
		}
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

	public String toString() {
		return toString(resource, label, quantity);
	}
	
	public static String toString(String resource, String label, int quantity) {
		// a label takes always priority
		if (label != null) {
			if (quantity > 0) {
				return "Label: " + label + ", Quantity: " + quantity;
			}
			return "Label: " + label;
		}
		// make sure there is an actual resource specified
		if (resource != null) {
			return resource;
		}
		return "[no resource/label specified - probably a bug]";
	}

	/**
	 * Label and resource are mutual exclusive.
	 */
	public void validate() throws Exception {
		validate(resource, label, quantity);
	}

	/**
	 * Label and resource are mutual exclusive.
	 */
	public static void validate(String resource, String label, int quantity) throws Exception {
		if (label != null && !label.isEmpty() && resource !=  null && !resource.isEmpty()) {
			throw new IllegalArgumentException("Label and resource name cannot be specified simultaneously.");
		}
	}

	private static final long serialVersionUID = 1L;

	@Extension
	public static class DescriptorImpl extends Descriptor<LockStepResource> {

		@Override
		public String getDisplayName() {
			return "Resource";
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

}
