/*
 * The MIT License
 *
 * Automatic resource creation based on node labels by Darius Mihai (mihai_darius22@yahoo.com)
 * Copyright (C) 2015 Freescale Semiconductor, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkins.plugins.lockableresources.autogen;

import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;

import java.util.Set;

import org.jenkins.plugins.lockableresources.WhitespaceSet;
import org.kohsuke.stapler.DataBoundConstructor;

public class LabelSet extends AbstractDescribableImpl<LabelSet> {

	/** A string containing all labels required */
	private final String labels;
	/** A set of labels that are required; same as 'labels', but labels are split by whitespace */
	private transient Set<String> labelsSet;

	@DataBoundConstructor
	public LabelSet(String labels) {
		this.labels		= Util.fixEmptyAndTrim(labels);
		this.labelsSet  = new WhitespaceSet(labels);
	}

	/**
	 * @return The set of labels in the current set
	 */
	public String getLabels() {
		return this.labels;
	}

	/**
	 * @return The set of labels in the current set
	 */
	public Set<String> getLabelsSet() {
		return this.labelsSet;
	}

	/**
	 * Used to update the 'labelsSet' based on the information in 'labels'
	 */
	public synchronized void updateLabelSet() {
		if(this.labels != null)
			this.labelsSet = new WhitespaceSet(this.labels);
		else
			this.labelsSet = new WhitespaceSet("");
	}

	@Override
	public int hashCode() {
		final int prime = 23;
		int result = 1;
		result = prime * result + ((labels == null) ? 0 : labels.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null)
			return false;

		if (this.getClass() != obj.getClass())
			return false;

		final LabelSet other = (LabelSet) obj;
		if ((this.labelsSet == null) ? (other.labelsSet != null) : !this.labelsSet.equals(other.labelsSet))
			return false;

		return true;
	}

	@Extension
	public static class DescriptorImpl extends Descriptor<LabelSet> {
		@Override
		public String getDisplayName() {
			return "Node label set";
		}
	}
}
