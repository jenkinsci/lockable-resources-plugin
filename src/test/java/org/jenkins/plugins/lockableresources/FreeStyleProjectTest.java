package org.jenkins.plugins.lockableresources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.User;
import hudson.model.queue.QueueTaskFuture;
import hudson.triggers.TimerTrigger;
import hudson.util.OneShotEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesQueueTaskDispatcher;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkinsci.plugins.scriptsecurity.scripts.ApprovalContext;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class FreeStyleProjectTest {

    // ---------------------------------------------------------------------------
    @BeforeEach
    void setUp() {
        // to speed up the test
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    }

    /**
     * Reproduction test for https://github.com/jenkinsci/lockable-resources-plugin/issues/1052.
     *
     * N freestyle jobs each need a distinct, immediately-available resource (by name). When they
     * are all submitted to the queue together the plugin's {@code canRun()} is evaluated for each
     * item. All resources are free, so all N should pass and start in quick succession.
     *
     * This is the named-resource control case: it exercises the simple {@code required}-list path
     * rather than the Groovy candidate-selection path.
     */
    @Test
    @Issue("1052")
    void parallelFreestyleDispatchWithAvailableResources(JenkinsRule j) throws Exception {
        final int N = 3;
        j.jenkins.setNumExecutors(N * 2);

        // N distinct resources, all immediately available (no holder builds).
        for (int i = 0; i < N; i++) {
            LockableResourcesManager.get().createResource("slot-" + i);
        }

        // Submit N builds simultaneously, each needing one distinct resource.
        // All resources are free, so all N should be dispatched in quick succession.
        List<FreeStyleProject> projects = new ArrayList<>();
        List<QueueTaskFuture<FreeStyleBuild>> futures = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            FreeStyleProject p = j.createFreeStyleProject("job-" + i);
            p.addProperty(new RequiredResourcesProperty("slot-" + i, null, null, null, null));
            projects.add(p);
        }
        for (int i = 0; i < N; i++) {
            futures.add(projects.get(i).scheduleBuild2(0));
        }

        // All N builds must complete successfully.
        long deadline = System.currentTimeMillis() + 30_000;
        for (int i = 0; i < N; i++) {
            long remaining = deadline - System.currentTimeMillis();
            assertTrue(remaining > 0, "Build " + i + " did not complete within 30s");
            j.assertBuildStatus(Result.SUCCESS, futures.get(i).get(remaining, TimeUnit.MILLISECONDS));
        }

        // All builds should have started close together, not serialised one per maintenance cycle.
        long firstStart = Long.MAX_VALUE;
        long lastStart = Long.MIN_VALUE;
        for (int i = 0; i < N; i++) {
            long t = futures.get(i).get().getStartTimeInMillis();
            firstStart = Math.min(firstStart, t);
            lastStart = Math.max(lastStart, t);
        }
        long spreadMs = lastStart - firstStart;
        assertTrue(spreadMs < 10_000, "Builds should start within 10s of each other; spread was " + spreadMs + "ms");
    }

    /**
     * Reproduction of the issue #1052 serialisation using the Groovy-script candidate-selection
     * path with a parameter-driven match script:
     *
     * <ul>
     *   <li>The match script is run in the Groovy <em>sandbox</em> ({@code sandbox=true}).</li>
     *   <li>The script reads per-build parameters:
     *       {@code return LOCK_ENVIRONMENT && resourceName == TARGET_ENVIRONMENT}.</li>
     *   <li>Each job has distinct parameter values so it matches exactly one distinct, free
     *       resource.</li>
     * </ul>
     *
     * All jobs share the identical script <em>text</em> and differ only by parameter values — a
     * common pattern where the resource to lock is selected by a build parameter. The symptom is
     * that, although each job needs a distinct and immediately-available resource, only one job
     * runs at a time — the next dispatches only after the previous build completes. A
     * start-time-spread assertion cannot detect this when builds are instantaneous, so each build
     * blocks on a shared latch and records peak concurrency:
     *
     * <ul>
     *   <li>Parallel dispatch: all N builds enter {@code perform()} together, peak concurrency N.</li>
     *   <li>Serialised dispatch: only the first build starts and blocks; the remaining N-1 never
     *       dispatch while the latch is held, so peak concurrency stays at 1.</li>
     * </ul>
     *
     * There are {@code N * 2} executors, so executor availability is never the constraint.
     */
    @Test
    @Issue("1052")
    void scriptResourceJobsRunConcurrently(JenkinsRule j) throws Exception {
        final int N = 12;
        j.jenkins.setNumExecutors(N * 2);

        for (int i = 0; i < N; i++) {
            LockableResourcesManager.get().createResource("env-slot-" + i);
        }

        final AtomicInteger concurrent = new AtomicInteger(0);
        final AtomicInteger maxConcurrent = new AtomicInteger(0);
        final CountDownLatch allStarted = new CountDownLatch(N);
        final CountDownLatch release = new CountDownLatch(1);

        // Match script shared by all jobs, run sandboxed and driven by build parameters.
        final String script = "return LOCK_ENVIRONMENT && resourceName == TARGET_ENVIRONMENT";

        List<FreeStyleProject> projects = new ArrayList<>();
        List<QueueTaskFuture<FreeStyleBuild>> futures = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            FreeStyleProject p = j.createFreeStyleProject("env-job-" + i);
            SecureGroovyScript groovyScript = new SecureGroovyScript(script, true, null);
            p.addProperty(new RequiredResourcesProperty(null, null, null, null, groovyScript));
            p.addProperty(new ParametersDefinitionProperty(
                    new StringParameterDefinition("LOCK_ENVIRONMENT", "true", ""),
                    new StringParameterDefinition("TARGET_ENVIRONMENT", "env-slot-" + i, "")));
            p.getBuildersList().add(new TestBuilder() {
                @Override
                public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                        throws InterruptedException {
                    int c = concurrent.incrementAndGet();
                    maxConcurrent.accumulateAndGet(c, Math::max);
                    allStarted.countDown();
                    // Hold the build open so a serialised dispatcher cannot "complete then dispatch next".
                    release.await(30, TimeUnit.SECONDS);
                    concurrent.decrementAndGet();
                    return true;
                }
            });
            projects.add(p);
        }
        for (int i = 0; i < N; i++) {
            futures.add(projects.get(i).scheduleBuild2(0));
        }

        // Wait (bounded) for all N builds to be running at once. If the bug serialises dispatch,
        // this times out with only one build ever having started.
        boolean allRunningTogether = allStarted.await(20, TimeUnit.SECONDS);
        int peak = maxConcurrent.get();

        // Let the builds finish regardless of outcome.
        release.countDown();
        for (int i = 0; i < N; i++) {
            j.assertBuildStatus(Result.SUCCESS, futures.get(i).get(30, TimeUnit.SECONDS));
        }

        assertTrue(
                allRunningTogether && peak == N,
                "Expected all " + N + " script-resource jobs to run concurrently, but peak concurrency "
                        + "was " + peak + " (allStarted=" + allRunningTogether + "). Peak of 1 reproduces "
                        + "the issue #1052 serialisation: only one job runs at a time.");
    }

    @Test
    @Issue("JENKINS-34853")
    void security170fix(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");
        FreeStyleProject p = j.createFreeStyleProject("p");
        p.addProperty(new RequiredResourcesProperty("resource1", "resourceNameVar", null, null, null));
        p.getBuildersList().add(new PrinterBuilder());

        FreeStyleBuild b1 = p.scheduleBuild2(0).get();
        j.assertLogContains("resourceNameVar: resource1", b1);
        j.assertBuildStatus(Result.SUCCESS, b1);
    }

    @Test
    void migrateToScript(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        FreeStyleProject p = j.createFreeStyleProject("p");
        p.addProperty(new RequiredResourcesProperty(null, null, null, "groovy:resourceName == 'resource1'", null));

        p.save();

        j.jenkins.reload();

        FreeStyleProject p2 = j.jenkins.getItemByFullName("p", FreeStyleProject.class);
        RequiredResourcesProperty newProp = p2.getProperty(RequiredResourcesProperty.class);
        assertNull(newProp.getLabelName());
        assertNotNull(newProp.getResourceMatchScript());
        assertEquals(
                "resourceName == 'resource1'", newProp.getResourceMatchScript().getScript());

        SemaphoreBuilder p2Builder = new SemaphoreBuilder();
        p2.getBuildersList().add(p2Builder);

        FreeStyleProject p3 = j.createFreeStyleProject("p3");
        p3.addProperty(new RequiredResourcesProperty("resource1", null, "1", null, null));
        SemaphoreBuilder p3Builder = new SemaphoreBuilder();
        p3.getBuildersList().add(p3Builder);

        final QueueTaskFuture<FreeStyleBuild> taskA = p3.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        TestHelpers.waitForQueue(j.jenkins, p3);
        final QueueTaskFuture<FreeStyleBuild> taskB = p2.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());

        p3Builder.release();
        final FreeStyleBuild buildA = taskA.get(60, TimeUnit.SECONDS);
        p2Builder.release();
        final FreeStyleBuild buildB = taskB.get(60, TimeUnit.SECONDS);

        long buildAEndTime = buildA.getStartTimeInMillis() + buildA.getDuration();
        assertTrue(
                buildB.getStartTimeInMillis() >= buildAEndTime,
                "Project A build should be finished before the build of project B starts. "
                        + "A finished at "
                        + buildAEndTime
                        + ", B started at "
                        + buildB.getStartTimeInMillis());
    }

    @Test
    void configRoundTripPlain(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        FreeStyleProject withResource = j.createFreeStyleProject("withResource");
        withResource.addProperty(new RequiredResourcesProperty("resource1", "resourceNameVar", null, null, null));
        FreeStyleProject withResourceRoundTrip = j.configRoundtrip(withResource);

        RequiredResourcesProperty withResourceProp = withResourceRoundTrip.getProperty(RequiredResourcesProperty.class);
        assertNotNull(withResourceProp);
        assertEquals("resource1", withResourceProp.getResourceNames());
        assertEquals("resourceNameVar", withResourceProp.getResourceNamesVar());
        assertNull(withResourceProp.getResourceNumber());
        assertNull(withResourceProp.getLabelName());
        assertNull(withResourceProp.getResourceMatchScript());
    }

    @Test
    void configRoundTripWithLabel(JenkinsRule j) throws Exception {
        FreeStyleProject withLabel = j.createFreeStyleProject("withLabel");
        withLabel.addProperty(new RequiredResourcesProperty(null, null, null, "some-label", null));
        FreeStyleProject withLabelRoundTrip = j.configRoundtrip(withLabel);

        RequiredResourcesProperty withLabelProp = withLabelRoundTrip.getProperty(RequiredResourcesProperty.class);
        assertNotNull(withLabelProp);
        assertNull(withLabelProp.getResourceNames());
        assertNull(withLabelProp.getResourceNamesVar());
        assertNull(withLabelProp.getResourceNumber());
        assertEquals("some-label", withLabelProp.getLabelName());
        assertNull(withLabelProp.getResourceMatchScript());
    }

    @Test
    void configRoundTripWithScript(JenkinsRule j) throws Exception {
        FreeStyleProject withScript = j.createFreeStyleProject("withScript");
        SecureGroovyScript origScript = new SecureGroovyScript("return true", false, null);
        withScript.addProperty(new RequiredResourcesProperty(null, null, null, null, origScript));
        FreeStyleProject withScriptRoundTrip = j.configRoundtrip(withScript);

        RequiredResourcesProperty withScriptProp = withScriptRoundTrip.getProperty(RequiredResourcesProperty.class);
        assertNotNull(withScriptProp);
        assertNull(withScriptProp.getResourceNames());
        assertNull(withScriptProp.getResourceNamesVar());
        assertNull(withScriptProp.getResourceNumber());
        assertNull(withScriptProp.getLabelName());
        assertNotNull(withScriptProp.getResourceMatchScript());
        assertEquals("return true", withScriptProp.getResourceMatchScript().getScript());
        assertFalse(withScriptProp.getResourceMatchScript().isSandbox());
    }

    @Test
    void approvalRequired(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource(LockableResourcesRootAction.ICON);

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());

        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .toAuthenticated()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("bob")
                .grant(Item.CONFIGURE, Item.BUILD)
                .everywhere()
                .to("alice"));

        final String SCRIPT =
                "resourceName == " + "org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction.ICON;";

        FreeStyleProject p = j.createFreeStyleProject();
        SecureGroovyScript groovyScript =
                new SecureGroovyScript(SCRIPT, true, null).configuring(ApprovalContext.create());

        p.addProperty(new RequiredResourcesProperty(null, null, null, null, groovyScript));

        User.getOrCreateByIdOrFullName("alice");
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.login("alice");

        QueueTaskFuture<FreeStyleBuild> futureBuild = p.scheduleBuild2(0);
        TestHelpers.waitForQueue(j.jenkins, p, Queue.BlockedItem.class);

        Queue.BlockedItem blockedItem = (Queue.BlockedItem) j.jenkins.getQueue().getItem(p);
        assertThat(
                blockedItem.getCauseOfBlockage(),
                is(instanceOf(LockableResourcesQueueTaskDispatcher.BecauseResourcesQueueFailed.class)));

        ScriptApproval approval = ScriptApproval.get();
        List<ScriptApproval.PendingSignature> pending = new ArrayList<>(approval.getPendingSignatures());

        assertFalse(pending.isEmpty());
        assertEquals(1, pending.size());
        ScriptApproval.PendingSignature firstPending = pending.get(0);

        assertEquals(
                "staticField org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction " + "ICON",
                firstPending.signature);
        approval.approveSignature(firstPending.signature);

        j.assertBuildStatusSuccess(futureBuild);
    }

    @Test
    void autoCreateResource(JenkinsRule j) throws Exception {
        FreeStyleProject f = j.createFreeStyleProject("f");
        assertNull(LockableResourcesManager.get().fromName("resource1"));
        f.addProperty(new RequiredResourcesProperty("resource1", null, null, null, null));
        assertNull(LockableResourcesManager.get().fromName("resource1"));

        FreeStyleBuild fb1 = f.scheduleBuild2(0).waitForStart();
        j.waitForMessage("acquired lock on [resource1]", fb1);
        j.waitForCompletion(fb1);

        assertNull(LockableResourcesManager.get().fromName("resource1"));
    }

    @Test
    void competingLabelLocks(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "group1");
        LockableResourcesManager.get().createResourceWithLabel("resource2", "group2");
        LockableResourcesManager.get().createResource("shared");
        LockableResource shared = LockableResourcesManager.get().fromName("shared");
        shared.setEphemeral(false);
        shared.getLabelsAsList().addAll(List.of("group1", "group2"));
        FreeStyleProject f0 = j.createFreeStyleProject("f0");
        final Semaphore semaphore = new Semaphore(1);
        f0.addProperty(new RequiredResourcesProperty("shared", null, null, null, null));
        f0.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws InterruptedException {
                semaphore.acquire();
                return true;
            }
        });
        FreeStyleProject f1 = j.createFreeStyleProject("f1");
        f1.addProperty(new RequiredResourcesProperty(null, null, "0", "group1", null));
        FreeStyleProject f2 = j.createFreeStyleProject("f2");
        f2.addProperty(new RequiredResourcesProperty(null, null, "0", "group2", null));

        semaphore.acquire();
        FreeStyleBuild fb0 = f0.scheduleBuild2(0).waitForStart();
        j.waitForMessage("acquired lock on [shared]", fb0);
        QueueTaskFuture<FreeStyleBuild> fb1q = f1.scheduleBuild2(0);
        QueueTaskFuture<FreeStyleBuild> fb2q = f2.scheduleBuild2(0);

        semaphore.release();
        j.waitForCompletion(fb0);
        // fb1 or fb2 might run first, it shouldn't matter as long as they both get the resource
        FreeStyleBuild fb1 = fb1q.waitForStart();
        FreeStyleBuild fb2 = fb2q.waitForStart();
        j.waitForMessage("acquired lock on [resource1, shared]", fb1);
        j.waitForCompletion(fb1);
        j.waitForMessage("acquired lock on [resource2, shared]", fb2);
        j.waitForCompletion(fb2);
    }

    // ---------------------------------------------------------------------------
    // Parameterized resource tests (build parameters as resource / label / number)
    // ---------------------------------------------------------------------------

    @Test
    void parameterizedResourceName(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("my-resource");

        FreeStyleProject p = j.createFreeStyleProject("paramResourceName");
        p.addProperty(new RequiredResourcesProperty("${RESOURCE_NAME}", "resourceNameVar", null, null, null));
        p.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("RESOURCE_NAME", "my-resource", "Resource to lock")));
        p.getBuildersList().add(new PrinterBuilder());

        FreeStyleBuild b = p.scheduleBuild2(0).get();
        j.assertLogContains("acquired lock on [my-resource]", b);
        j.assertLogContains("resourceNameVar: my-resource", b);
        j.assertBuildStatus(Result.SUCCESS, b);
    }

    @Test
    void parameterizedLabel(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("res1", "team-alpha");
        LockableResourcesManager.get().createResourceWithLabel("res2", "team-alpha");

        FreeStyleProject p = j.createFreeStyleProject("paramLabel");
        p.addProperty(new RequiredResourcesProperty(null, "resourceNameVar", "1", "${LABEL}", null));
        p.addProperty(
                new ParametersDefinitionProperty(new StringParameterDefinition("LABEL", "team-alpha", "Label to use")));

        FreeStyleBuild b = p.scheduleBuild2(0).get();
        j.assertLogContains("acquired lock on", b);
        j.assertBuildStatus(Result.SUCCESS, b);
    }

    @Test
    void parameterizedResourceNumber(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("pool1", "pool");
        LockableResourcesManager.get().createResourceWithLabel("pool2", "pool");
        LockableResourcesManager.get().createResourceWithLabel("pool3", "pool");

        FreeStyleProject p = j.createFreeStyleProject("paramNumber");
        p.addProperty(new RequiredResourcesProperty(null, "resourceNameVar", "${COUNT}", "pool", null));
        p.addProperty(
                new ParametersDefinitionProperty(new StringParameterDefinition("COUNT", "2", "How many resources")));

        FreeStyleBuild b = p.scheduleBuild2(0).get();
        j.assertLogContains("acquired lock on", b);
        j.assertBuildStatus(Result.SUCCESS, b);

        // Verify exactly 2 resources were locked via the variable
        String log = b.getLog();
        String varLine = null;
        for (String line : log.split("\n")) {
            if (line.contains("acquired lock on")) {
                varLine = line;
                break;
            }
        }
        assertNotNull(varLine, "Expected 'acquired lock on' in build log");
        // Count resource names in the log line (comma-separated inside brackets)
        long count = varLine.chars().filter(ch -> ch == ',').count() + 1;
        assertEquals(2, count, "Expected exactly 2 resources to be locked");
    }

    public static class PrinterBuilder extends TestBuilder {

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException, IOException {
            listener.getLogger()
                    .println(
                            "resourceNameVar: " + build.getEnvironment(listener).get("resourceNameVar"));
            return true;
        }
    }

    private static class SemaphoreBuilder extends TestBuilder {

        private final OneShotEvent event = new OneShotEvent();

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException {
            event.block();
            return true;
        }

        void release() {
            event.signal();
        }
    }
}
