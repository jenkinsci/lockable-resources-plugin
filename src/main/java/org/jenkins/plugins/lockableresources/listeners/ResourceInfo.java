/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.listeners;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourceProperty;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;

/**
 * Immutable, read-only snapshot of a {@link LockableResource} that is safe to pass to Groovy
 * callback scripts. Exposes only getter methods so that scripts cannot modify the resource state.
 */
public class ResourceInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final String description;
    private final String note;
    private final List<String> labels;
    private final Map<String, String> properties;

    public ResourceInfo(@NonNull LockableResource resource) {
        this.name = resource.getName();
        this.description = resource.getDescription();
        this.note = resource.getNote();
        this.labels = resource.getLabelsAsList() != null
                ? Collections.unmodifiableList(resource.getLabelsAsList())
                : Collections.emptyList();

        Map<String, String> props = new LinkedHashMap<>();
        if (resource.getProperties() != null) {
            for (LockableResourceProperty p : resource.getProperties()) {
                props.put(p.getName(), p.getValue());
            }
        }
        this.properties = Collections.unmodifiableMap(props);
    }

    @Whitelisted
    public String getName() {
        return name;
    }

    @Whitelisted
    public String getDescription() {
        return description;
    }

    @Whitelisted
    public String getNote() {
        return note;
    }

    @Whitelisted
    public List<String> getLabels() {
        return labels;
    }

    @Whitelisted
    public Map<String, String> getProperties() {
        return properties;
    }

    @Whitelisted
    public String getProperty(String key) {
        return properties.get(key);
    }

    @Whitelisted
    @Override
    public String toString() {
        return "ResourceInfo{name='" + name + "'}";
    }
}
