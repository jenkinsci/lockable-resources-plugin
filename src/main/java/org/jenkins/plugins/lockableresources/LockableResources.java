/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import hudson.Plugin;
import hudson.model.Api;
import java.util.List;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

@ExportedBean
public class LockableResources extends Plugin {

    public Api getApi() {
        return new Api(this);
    }

    @Exported
    public List<LockableResource> getResources() {
        return LockableResourcesManager.get().getReadOnlyResources();
    }
}
