package org.jenkins.plugins.lockableresources.actions;

import hudson.model.Action;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import org.jenkins.plugins.lockableresources.Messages;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

// -----------------------------------------------------------------------------
/** BuildAction for lockable resources.
 * Shows usage of resources in the build page.
 * url: jobUrl/buildNr/locked-resources/
 */
@Restricted(NoExternalUse.class)
public class LockedResourcesBuildAction implements Action {

    private static final long serialVersionUID = 1L;

    private List<LogEntry> logs = new ArrayList<>();
    private final transient Object syncLogs = new Object();
    private List<String> resourcesInUse = new ArrayList<>();

    public LockedResourcesBuildAction() {}

    // -------------------------------------------------------------------------
    @Override
    public String getIconFileName() {
        return LockableResourcesRootAction.ICON;
    }

    // -------------------------------------------------------------------------
    @Override
    public String getDisplayName() {
        return Messages.LockedResourcesBuildAction_displayName();
    }

    // -------------------------------------------------------------------------
    @Override
    public String getUrlName() {
        return "locked-resources";
    }

    public List<String> getCurrentUsedResourceNames() {
        return resourcesInUse;
    }

    public void addUsedResources(List<String> resourceNames) {
        synchronized (resourcesInUse) {
            resourcesInUse.addAll(resourceNames);
        }
    }

    public void removeUsedResources(List<String> resourceNames) {
        synchronized (resourcesInUse) {
            resourcesInUse.removeAll(resourceNames);
        }
    }

    public static LockedResourcesBuildAction findAndInitAction(final Run<?, ?> build) {
        if (build == null) {
            return null;
        }
        LockedResourcesBuildAction action;
        synchronized (build) {
            List<LockedResourcesBuildAction> actions = build.getActions(LockedResourcesBuildAction.class);

            if (actions.size() <= 0) {
                action = new LockedResourcesBuildAction();
                build.addAction(action);
            } else {
                action = actions.get(0);
            }
        }
        return action;
    }

    public static void addLog(
            final Run<?, ?> build, final List<String> resourceNames, final String step, final String action) {

        for (String resourceName : resourceNames) addLog(build, resourceName, step, action);
    }

    public static void addLog(
            final Run<?, ?> build, final String resourceName, final String step, final String action) {

        LockedResourcesBuildAction buildAction = findAndInitAction(build);

        buildAction.addLog(resourceName, step, action);
    }

    public void addLog(final String resourceName, final String step, final String action) {

        synchronized (this.logs) {
            this.logs.add(new LogEntry(step, action, resourceName));
        }
    }

    @Restricted(NoExternalUse.class)
    public List<LogEntry> getReadOnlyLogs() {
        synchronized (this.logs) {
            return new ArrayList<>(Collections.unmodifiableCollection(this.logs));
        }
    }

    public static class LogEntry {

        private String step;
        private String action;
        private String resourceName;
        private long timeStamp;

        @Restricted(NoExternalUse.class)
        public LogEntry(final String step, final String action, final String resourceName) {
            this.step = step;
            this.action = action;
            this.resourceName = resourceName;
            this.timeStamp = new Date().getTime();
        }

        // ---------------------------------------------------------------------
        @Restricted(NoExternalUse.class)
        public String getName() {
            return this.resourceName;
        }

        // ---------------------------------------------------------------------
        @Restricted(NoExternalUse.class)
        public String getStep() {
            return this.step;
        }

        // ---------------------------------------------------------------------
        @Restricted(NoExternalUse.class)
        public String getAction() {
            return this.action;
        }

        // ---------------------------------------------------------------------
        @Restricted(NoExternalUse.class)
        public Date getTimeStamp() {
            return new Date(this.timeStamp);
        }
    }
}
