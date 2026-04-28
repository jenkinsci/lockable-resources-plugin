/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.listeners;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jenkins.plugins.lockableresources.LockableResource;

/**
 * A test listener that records all events for verification. Registered as an {@link Extension} so
 * it is automatically discovered by the Jenkins test harness.
 */
@Extension
public class TestResourceEventListener extends ResourceEventListener {

    /** Recorded event entry. */
    public static class EventRecord {
        public final ResourceEvent event;
        public final List<String> resourceNames;
        public final String buildName;
        public final String userName;

        EventRecord(
                ResourceEvent event, List<String> resourceNames, String buildName, String userName) {
            this.event = event;
            this.resourceNames = resourceNames;
            this.buildName = buildName;
            this.userName = userName;
        }

        @Override
        public String toString() {
            return event + " " + resourceNames + " build=" + buildName + " user=" + userName;
        }
    }

    private static final List<EventRecord> EVENTS = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onEvent(
            @NonNull ResourceEvent event,
            @NonNull List<LockableResource> resources,
            @Nullable Run<?, ?> build,
            @Nullable String userName) {
        List<String> names = new ArrayList<>();
        for (LockableResource r : resources) {
            names.add(r.getName());
        }
        EVENTS.add(new EventRecord(
                event, names, build != null ? build.getFullDisplayName() : null, userName));
    }

    /** Returns all recorded events. */
    public static List<EventRecord> getEvents() {
        return new ArrayList<>(EVENTS);
    }

    /** Returns all recorded events of the given type. */
    public static List<EventRecord> getEvents(ResourceEvent type) {
        List<EventRecord> filtered = new ArrayList<>();
        for (EventRecord r : EVENTS) {
            if (r.event == type) {
                filtered.add(r);
            }
        }
        return filtered;
    }

    /** Clears all recorded events. */
    public static void clear() {
        EVENTS.clear();
    }

    /**
     * Static field that Groovy callback scripts can write to via
     * {@code org.jenkins.plugins.lockableresources.listeners.TestResourceEventListener.callbackResult = ...}.
     * Used to verify Groovy callback execution from tests.
     */
    public static volatile String callbackResult = null;
}
