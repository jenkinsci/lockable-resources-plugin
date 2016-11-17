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

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.RootAction;
import java.util.Map;
import java.util.Set;
import org.jenkins.plugins.lockableresources.BackwardCompatibility;
import org.jenkins.plugins.lockableresources.LockableResources;
import org.jenkins.plugins.lockableresources.dynamicres.DynamicResourcesManager;
import org.kohsuke.stapler.export.Exported;

@Extension
public class DynamicResourcesRootAction implements RootAction {
    /**
     * Backward compatibility
     */
    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void initBackwardCompatibility() {
        BackwardCompatibility.init();
    }
    /* use the same icon as lockable resources */
    public static final String ICON = "/plugin/lockable-resources/img/device-24x24.png";

    @Override
    public String getIconFileName() {
        if(canRead()) {
            // only show if READ permission
            return ICON;
        } else {
            return null;
        }
    }

    @Exported
    public static boolean canRead() {
        return LockableResources.canRead();
    }

    @Override
    public String getDisplayName() {
        return "Dynamic Resources";
    }

    @Override
    public String getUrlName() {
        return "dynamic-resources";
    }

    /**
     * @return The dynamic resources available
     */
    public Set<Map<?, ?>> getDynamicResources() {
        return DynamicResourcesManager.getDynamicResources();
    }

    /**
     * @return The amount of dynamic resources available
     */
    public int getDynamicResourcesAmount() {
        return DynamicResourcesManager.getDynamicResources().size();
    }
}
