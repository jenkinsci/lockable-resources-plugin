package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import hudson.triggers.TimerTrigger;
import hudson.util.OneShotEvent;
import java.util.concurrent.TimeUnit;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class FreeStyleTimeoutTest {

    @BeforeEach
    void setUp() {
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    }

    /**
     * A freestyle job with a short lockTimeout should be cancelled from the queue
     * when the resource is not freed in time.
     */
    @Test
    void freestyleLockTimeoutCancelsQueueItem(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        // Job A grabs the resource and holds it
        FreeStyleProject jobA = j.createFreeStyleProject("jobA");
        jobA.addProperty(new RequiredResourcesProperty("resource1", null, null, null, null));
        SemaphoreBuilder builderA = new SemaphoreBuilder();
        jobA.getBuildersList().add(builderA);

        QueueTaskFuture<FreeStyleBuild> futureA = jobA.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        // Wait until jobA is actually running (has left the queue and locked the resource)
        TestHelpers.waitForQueue(j.jenkins, jobA);
        builderA.started.block(5000);
        assertTrue(builderA.started.isSignaled(), "jobA should have started");

        // Job B: 3-second timeout
        FreeStyleProject jobB = j.createFreeStyleProject("jobB");
        RequiredResourcesProperty prop = new RequiredResourcesProperty("resource1", null, null, null, null);
        prop.setLockTimeout(3);
        prop.setLockTimeoutUnit("SECONDS");
        jobB.addProperty(prop);
        jobB.getBuildersList().add(new NoopBuilder());

        jobB.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        TestHelpers.waitForQueue(j.jenkins, jobB);

        // Poll until the queue item for jobB is cancelled (timeout: 30s to handle slow CI)
        long deadline = System.currentTimeMillis() + 30_000;
        while (j.jenkins.getQueue().getItem(jobB) != null) {
            assertTrue(
                    System.currentTimeMillis() < deadline,
                    "jobB should have been removed from the queue after timeout");
            Thread.sleep(500);
        }

        // Release jobA
        builderA.release();
        FreeStyleBuild buildA = futureA.get(30, TimeUnit.SECONDS);
        j.assertBuildStatusSuccess(buildA);
    }

    /**
     * A freestyle job with lockTimeout=0 (default) waits indefinitely.
     */
    @Test
    void freestyleNoTimeoutWaitsIndefinitely(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        // Job A grabs the resource
        FreeStyleProject jobA = j.createFreeStyleProject("jobA");
        jobA.addProperty(new RequiredResourcesProperty("resource1", null, null, null, null));
        SemaphoreBuilder builderA = new SemaphoreBuilder();
        jobA.getBuildersList().add(builderA);

        QueueTaskFuture<FreeStyleBuild> futureA = jobA.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        TestHelpers.waitForQueue(j.jenkins, jobA);
        builderA.started.block(5000);

        // Job B: no timeout → should stay in queue
        FreeStyleProject jobB = j.createFreeStyleProject("jobB");
        jobB.addProperty(new RequiredResourcesProperty("resource1", null, null, null, null));
        jobB.getBuildersList().add(new NoopBuilder());

        jobB.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        TestHelpers.waitForQueue(j.jenkins, jobB);

        // Wait a bit and verify jobB is still in the queue
        Thread.sleep(2000);
        assertNotNull(j.jenkins.getQueue().getItem(jobB), "jobB should still be in the queue (no timeout)");

        // Release jobA → jobB should proceed
        builderA.release();
        FreeStyleBuild buildA = futureA.get(30, TimeUnit.SECONDS);
        j.assertBuildStatusSuccess(buildA);

        // Wait for B to complete
        j.waitUntilNoActivity();
        FreeStyleBuild buildB = jobB.getLastBuild();
        assertNotNull(buildB, "jobB should have run after jobA released the resource");
        assertEquals(Result.SUCCESS, buildB.getResult());
    }

    /**
     * Config round-trip: lockTimeout and lockTimeoutUnit survive save/reload.
     */
    @Test
    void configRoundTripWithTimeout(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        FreeStyleProject p = j.createFreeStyleProject("withTimeout");
        RequiredResourcesProperty prop = new RequiredResourcesProperty("resource1", null, null, null, null);
        prop.setLockTimeout(5);
        prop.setLockTimeoutUnit("MINUTES");
        p.addProperty(prop);

        FreeStyleProject roundTripped = j.configRoundtrip(p);
        RequiredResourcesProperty rtProp = roundTripped.getProperty(RequiredResourcesProperty.class);
        assertNotNull(rtProp);
        assertEquals("resource1", rtProp.getResourceNames());
        assertEquals(5, rtProp.getLockTimeout());
        assertEquals("MINUTES", rtProp.getLockTimeoutUnit());
    }

    private static class SemaphoreBuilder extends TestBuilder {
        final OneShotEvent started = new OneShotEvent();
        private final OneShotEvent event = new OneShotEvent();

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException {
            started.signal();
            event.block();
            return true;
        }

        void release() {
            event.signal();
        }
    }

    private static class NoopBuilder extends TestBuilder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            return true;
        }
    }
}
