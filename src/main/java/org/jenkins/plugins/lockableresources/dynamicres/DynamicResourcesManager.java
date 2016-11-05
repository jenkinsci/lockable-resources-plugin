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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class DynamicResourcesManager {
    /**
     * Set of available dynamic resources. Dynamic resources are used only at runtime, so should not
     * be saved on permanent memory
     */
    private static final transient Set<Map<?, ?>> dynamicResources = new HashSet<Map<?, ?>>();
    /**
     * A map containing information about the resources that will be created or consumed by a random
     * job. The job is identified using an unique name that can be obtained using DynamicUtils.
     * getUniqueName.
     */
    private static final transient Map<String, DynamicInfo> dynamicResourcesInfo = new HashMap<String, DynamicInfo>();

    /**
     * @return The set of available dynamicResources
     */
    public static Set<Map<?, ?>> getDynamicResources() {
        return dynamicResources;
    }

    /**
     * @param config The configuration for the dynamic resource to be created.
     *               Empty or null configurations always return false.
     *
     * @return True if a dynamic resource for the given configuration is successfully added to the dynamic
     *         resources pool, or false otherwise
     */
    public static synchronized Boolean createDynamicResource(Map<?, ?> config) {
        if(config == null || config.isEmpty()) {
            return false;
        }

        return dynamicResources.add(config);
    }

    /**
     * @param configs A collection of dynamic resources configurations to create.
     *                Empty or null collections are ignored.
     *
     * @return True if the configurations have been successfully added to the pool
     *         of dynamic resources, or false otherwise
     */
    public static synchronized Boolean createDynamicResources(Collection<Map<?, ?>> configs) {
        if(configs == null || configs.isEmpty()) {
            return false;
        }

        return dynamicResources.addAll(configs);
    }

    /**
     * @param config The configuration for the dynamic resource to remove.
     *               Empty or null configurations always return false.
     *
     * @return True if a dynamic resource for the given configuration is successfully removed from the pool
     *         of dynamic resources, or false otherwise
     */
    public static synchronized boolean consumeDynamicResource(Map<?, ?> config) {
        if(config == null || config.isEmpty()) {
            return false;
        }

        return dynamicResources.remove(config);
    }

    /**
     * @param configs A collection of dynamic resources configurations to consume.
     *                Empty or null collections are ignored.
     *
     * @return True if the configurations have been successfully removed from the pool
     *         of dynamic resources, or false otherwise
     */
    public static synchronized Boolean consumeDynamicResources(Collection<Map<?, ?>> configs) {
        if(configs == null || configs.isEmpty()) {
            return false;
        }

        return dynamicResources.removeAll(configs);
    }

    /**
     * @param config The configuration of a possible dynamic resource.
     *               Empty or null configurations always return false.
     *
     * @return True if a dynamic resource for the given configuration is found, or false otherwise
     */
    public static synchronized boolean checkDynamicResource(Map<?, ?> config) {
        if(config == null || config.isEmpty()) {
            return false;
        }

        return dynamicResources.contains(config);
    }

    /**
     * @param configs A collection of dynamic resources configurations to check.
     *                Empty or null collections are ignored.
     *
     * @return True if all configurations are found in the pool of dynamic resources, or false otherwise
     */
    public static synchronized boolean checkDynamicResources(Collection<Map<?, ?>> configs) {
        if(configs == null || configs.isEmpty()) {
            return false;
        }

        return dynamicResources.containsAll(configs);
    }

    /**
     * This method will clear the dynamic resources list. ALL dynamic resources will be lost
     */
    public static synchronized void destroyAllDynamicResources() {
        dynamicResources.clear();
    }

    /**
     * @return All available information about dynamic resource creation and consumption
     */
    public static Map<String, DynamicInfo> getDynamicResourcesInfo() {
        return dynamicResourcesInfo;
    }

    /**
     * @param jobName The job whose dynamic resources information is required
     *
     * @return An object containing the dynamic resource configurations expected
     *         to be created or consumed by the job
     */
    public static synchronized DynamicInfo getJobDynamicInfo(String jobName) {
        return dynamicResourcesInfo.get(jobName);
    }

    /**
     * @param jobName The job whose dynamic resources creation information is required
     *
     * @return A set of dynamic resource configurations expected to be created by the job
     */
    public static synchronized Set<Map<?, ?>> getJobWillCreate(String jobName) {
        return dynamicResourcesInfo.get(jobName).getWillCreate();
    }

    /**
     * @param jobName The job whose dynamic resources consumption information is required
     *
     * @return A set of dynamic resource configurations expected to be consumed by the job
     */
    public static synchronized Set<Map<?, ?>> getJobWillConsume(String jobName) {
        return dynamicResourcesInfo.get(jobName).getWillConsume();
    }

    /**
     * Method used to change the dynamic resources information for the job with the given name.
     *
     * @param jobName     The name of the job whose information is updated
     * @param willCreate  Resources expected to be created by the job
     * @param willConsume Resources expected to be consumed by the job
     */
    public static synchronized void setJobDynamicInfo(String jobName,
            Set<Map<?, ?>> willCreate,
            Set<Map<?, ?>> willConsume) {
        dynamicResourcesInfo.put(jobName, new DynamicInfo(willCreate, willConsume));
    }

    /**
     * Method used to remove the dynamic resources information for the job with the given name.
     *
     * @param jobName The name of the job that will have its information removed
     */
    public static synchronized void destroyJobDynamicInfo(String jobName) {
        dynamicResourcesInfo.remove(jobName);
    }
}
