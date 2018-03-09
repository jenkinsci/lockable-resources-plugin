package org.jenkins.plugins.lockableresources;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class LockStepData implements Serializable {
    private String name;
    private String description;
    private String labels;
    private String reservedBy = null;
    private String reservedByEmail;
    private Map<String, String> properties = new HashMap<String, String>();

    public static LockStepData from(LockableResource res) {
        LockStepData d = new LockStepData();
        d.name = res.getName();
        d.description = res.getDescription();
        d.labels = res.getLabels();
        d.reservedBy = res.getReservedBy();
        d.reservedByEmail = res.getReservedByEmail();
        for (LockableResourceProperty lrp : res.getProperties()) {
            d.properties.put(lrp.getName(), lrp.getValue());
        }
        return d;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getLabels() {
        return labels;
    }

    public String getReservedBy() {
        return reservedBy;
    }

    public String getReservedByEmail() {
        return reservedByEmail;
    }

    public Map<String, String> getProperties() {
        return properties==null?Collections.<String, String>emptyMap():properties;
    }

    private static final long serialVersionUID = 1L;
}
