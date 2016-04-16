/*
 * The MIT License
 *
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
package org.jenkins.plugins.lockableresources.dynamicres.actions;

import hudson.model.AbstractBuild;
import hudson.model.Action;

import java.util.Map;
import java.util.Set;

import org.jenkins.plugins.lockableresources.dynamicres.DynamicInfo;
import org.jenkins.plugins.lockableresources.dynamicres.DynamicResourcesManager;
import org.jenkins.plugins.lockableresources.dynamicres.DynamicUtils;

public class DynamicResourcesBuildAction implements Action {

	private final DynamicInfo info;

	public DynamicResourcesBuildAction(AbstractBuild<?, ?> build) {
		String uniqueJobName = DynamicUtils.getUniqueName(build);

		info = DynamicResourcesManager.getJobDynamicInfo(uniqueJobName);
	}

	public String getIconFileName() {
		return DynamicResourcesRootAction.ICON;
	}

	public String getDisplayName() {
		return "Dynamic Resources Status";
	}

	public String getUrlName() {
		return "dynamic-resources-status";
	}

	/**
	 * @return The dynamic resources that will be created by this build
	 */
	public Set<Map<?, ?>> getCreatedByJob() {
		return info.getWillCreate();
	}

	/**
	 * @return The dynamic resources that will be consumed by this build
	 */
	public Set<Map<?, ?>> getConsumedByJob() {
		return info.getWillConsume();
	}

	/**
	 * @return The amount of dynamic resources that will be created by this build
	 */
	public int getCreatedAmount() {
		return info.getWillCreate().size();
	}

	/**
	 * @return The dynamic amount of resources that will be consumed by this build
	 */
	public int getConsumedAmount() {
		return info.getWillConsume().size();
	}
}
