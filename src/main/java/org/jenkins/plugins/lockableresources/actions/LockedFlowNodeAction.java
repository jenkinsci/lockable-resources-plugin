package org.jenkins.plugins.lockableresources.actions;

import hudson.model.InvisibleAction;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public class LockedFlowNodeAction extends InvisibleAction {
    private List<String> resourceNames = new ArrayList<>();
    private boolean inversePrecedence;
    private String resourceDescription;
    private boolean released;

    public LockedFlowNodeAction(@Nonnull List<String> resourceNames, @CheckForNull String resourceDescription,
                                boolean inversePrecedence) {
        this.resourceNames.addAll(resourceNames);
        this.resourceDescription = resourceDescription;
        this.inversePrecedence = inversePrecedence;
    }

    @Nonnull
    public List<String> getResourceNames() {
        return resourceNames;
    }

    @CheckForNull
    public String getResourceDescription() {
        return resourceDescription;
    }

    public boolean isInversePrecedence() {
        return inversePrecedence;
    }

    public void release() {
        this.released = true;
    }

    public boolean isReleased() {
        return released;
    }
}
