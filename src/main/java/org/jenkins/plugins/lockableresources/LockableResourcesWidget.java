/*
 * The MIT License
 *
 * Copyright 2016 Eb.
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
package org.jenkins.plugins.lockableresources;

import hudson.security.Permission;
import hudson.widgets.Widget;
import java.util.Set;
import javax.annotation.CheckForNull;
import org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;
import org.kohsuke.stapler.export.Exported;

/**
 *
 * @author Eb
 */
public class LockableResourcesWidget extends Widget {
    public static final Permission UNLOCK = LockableResourcesRootAction.UNLOCK;
    public static final Permission RESERVE = LockableResourcesRootAction.RESERVE;
    public static final Permission OFFLINE = LockableResourcesRootAction.OFFLINE;
    
    @Exported
    public Set<LockableResource> getResources() {
        LockableResourcesManager manager = LockableResourcesManager.get();
        return manager.getAllResources();
    }
    
    @Exported
    @CheckForNull
    public static String getUserId() {
        return LockableResourcesRootAction.getUserId();
    }
}
