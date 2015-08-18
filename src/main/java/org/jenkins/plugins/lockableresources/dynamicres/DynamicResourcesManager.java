/*
 * The MIT License
 *
 * Dynamic resources management by Darius Mihai (mihai_darius22@yahoo.com)
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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class DynamicResourcesManager {
	/** Set of available dynamic resources. Dynamic resources are used only at runtime, so should not
	 *  be saved on permanent memory
	 */
	private static final transient Set<Map<?, ?>> dynamicResources = new HashSet<Map<?, ?>>();

	/**
	 * @param config The configuration for the dynamic resource to be created.
	 * Empty or null configurations always return false.
	 * @return True if a dynamic resource for the given configuration is successfully added to the dynamic
	 * resources pool, or false otherwise
	 */
	public static synchronized Boolean createDynamicResource(Map<?, ?> config) {
		if (config == null || config.isEmpty())
			return false;

		return dynamicResources.add(config);
	}

	/**
	 * @param config The configuration for the dynamic resource to remove.
	 * Empty or null configurations always return false.
	 * @return True if a dynamic resource for the given configuration is successfully removed from the pool
	 * of dynamic resources, or false otherwise
	 */
	public static synchronized boolean consumeDynamicResource(Map<?, ?> config) {
		if (config == null || config.isEmpty())
			return false;

		return dynamicResources.remove(config);
	}

	/**
	 * @param config The configuration of a possible dynamic resource.
	 * Empty or null configurations always return false.
	 * @return True if a dynamic resource for the given configuration is found, or false otherwise
	 */
	public static synchronized boolean checkDynamicResource(Map<?, ?> config) {
		if (config == null || config.isEmpty())
			return false;

		return dynamicResources.contains(config);
	}

	/**
	 * This method will clear the dynamic resources list. ALL dynamic resources will be lost
	 */
	public static synchronized void destroyAllDynamicResources() {
		dynamicResources.clear();
	}
}
