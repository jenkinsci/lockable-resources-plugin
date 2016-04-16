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
package org.jenkins.plugins.lockableresources.dynamicres;

import hudson.model.AbstractBuild;
import hudson.model.Queue;

import java.util.Map.Entry;
import java.util.Set;

/**
 * Class used to store data that will be used when requesting or updating dynamic resources
 * information. Contains the real name of the job as 'jobName', according to Jenkins, an (unique) name
 * that will be used to store information about the job as 'uniqueJobName', the value of the
 * token created for the job as 'tokenValue' and the matrix configuration as 'configuration'
 */
public class DynamicInfoData {

	/** The name of a job (the job name can be extracted from a Queue.Item's task, or a build's project) */
	public final String jobName;
	/** The matrix configuration for the considered project */
	public final Set<Entry<String, String>> configuration;
	/** An unique name used to identify the job when retrieving dynamic resources information */
	public  final String uniqueJobName;
	/** The value of the token created for the job */
	public  final String tokenValue;

	public DynamicInfoData(DynamicResourcesProperty property, Queue.Item item) {
		this.jobName       = DynamicUtils.getJobName(item);
		this.configuration = DynamicUtils.getProjectConfiguration(item);
		this.tokenValue    = DynamicUtils.getProjectToken(item);
		this.uniqueJobName = DynamicUtils.getUniqueName(item);
	}

	public DynamicInfoData(DynamicResourcesProperty property, AbstractBuild<?, ?> build) {
		this.jobName       = DynamicUtils.getJobName(build);
		this.configuration = DynamicUtils.getProjectConfiguration(build);
		this.tokenValue	   = DynamicUtils.getProjectToken(build);
		this.uniqueJobName = DynamicUtils.getUniqueName(build);
	}
}
