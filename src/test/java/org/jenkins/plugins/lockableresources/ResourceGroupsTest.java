package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import hudson.EnvVars;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.actions.GroupLockFailureAction;
import org.jenkins.plugins.lockableresources.actions.ResourceVariableNameAction;
import org.jenkins.plugins.lockableresources.queue.LockRunListener;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkins.plugins.lockableresources.remote.RemoteLockManager;
import org.jenkins.plugins.lockableresources.remote.RemoteLockRecord;
import org.jenkins.plugins.lockableresources.remote.RemoteLockRequest;
import org.jenkins.plugins.lockableresources.remote.RemoteLockState;
import org.jenkins.plugins.lockableresources.remote.RemoteResolver;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ResourceGroupsTest {
    private static final Logger LOGGER = Logger.getLogger(ResourceGroupsTest.class.getName());

    @Test
    void groupOnlyPipelineAndFreestyleSelectWholeGroup(JenkinsRule j) throws Exception {
        add("protected-one", "production", "");
        add("protected-two", "production", "");
        LockableResource ordinary = add("ordinary", null, "");
        WorkflowJob job = j.createProject(WorkflowJob.class, "whole-group");
        job.setDefinition(new CpsFlowDefinition("""
                lock(group: 'production', variable: 'DEVICE') {
                  echo "selected=${env.DEVICE}"
                  semaphore 'whole-group'
                }
                """, true));
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("whole-group/1", run);
        j.assertLogContains("selected=protected-one,protected-two", run);
        assertTrue(ordinary.isFree());
        SemaphoreStep.success("whole-group/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(run));
        RequiredResourcesProperty property = new RequiredResourcesProperty(null, null, null, null, null);
        property.setGroup("production");
        var freestyle = j.createFreeStyleProject("whole-group-freestyle");
        freestyle.addProperty(property);
        var build = j.buildAndAssertSuccess(freestyle);
        j.assertLogContains("acquired lock on [protected-one, protected-two]", build);
        LockStep empty = new LockStep(null);
        assertThrows(IllegalArgumentException.class, () -> empty.validate(false));
        empty.setGroup("missing");
        assertThrows(IllegalArgumentException.class, () -> empty.validate(false));
    }

    @Test
    void refreshMembershipRechecksPreviouslyQueuedFreestyleCandidates(JenkinsRule j) throws Exception {
        LockableResource ordinary = add("ordinary", null, "device");
        LockableResource replacement = add("replacement", null, "device");
        LockableResourcesManager manager = LockableResourcesManager.get();
        LockableResourcesStruct requirement = selector(null, 1);
        assertEquals(List.of(ordinary), manager.tryQueue(requirement, 201, "job", 1, Map.of(), LOGGER));
        ordinary.setGroup("production");
        manager.refreshQueue();
        assertEquals(List.of(replacement), manager.tryQueue(requirement, 201, "job", 1, Map.of(), LOGGER));
        assertFalse(ordinary.isQueued());
        assertTrue(replacement.isQueuedByTask(201));
        replacement.unqueue();
        assertTrue(manager.queue(List.of(ordinary), 202, "named", "production"));
        ordinary.setGroup("other");
        assertThrows(
                IllegalArgumentException.class, () -> manager.queue(List.of(ordinary), 202, "named", "production"));
        assertFalse(ordinary.isQueued());
    }

    @Test
    void taskCleanupPreservesOtherQueueAssignments(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        LockableResource first = add("first", null, "device");
        LockableResource second = add("second", null, "device");
        LockableResource otherTask = add("other-task", null, "device");
        first.setQueued(201, "first-job");
        second.setQueued(201, "first-job");
        otherTask.setQueued(202, "second-job");

        first.setGroup("production");
        assertThrows(IllegalArgumentException.class, () -> manager.queue(List.of(first), 201, "first-job"));
        assertFalse(first.isQueued());
        assertTrue(second.isQueuedByTask(201), "named queue failure only clears the supplied resources");
        assertTrue(otherTask.isQueuedByTask(202));

        assertThrows(
                ExecutionException.class,
                () -> manager.tryQueue(selector("missing", 1), 201, "first-job", 1, Map.of(), LOGGER));
        assertFalse(second.isQueued(), "selector validation failure clears all assignments for the task");
        assertTrue(otherTask.isQueuedByTask(202));

        manager.unqueueTask(202);
        manager.unqueueTask(202);
        assertFalse(otherTask.isQueued());
    }

    @Test
    void freestyleStartRechecksGroupAfterQueueAdmission(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        long queueId = 300;
        for (String group : List.of("common", "production")) {
            for (String mode : List.of("label", "script", "group", "named")) {
                String name = group + "-" + mode;
                LockableResource moved = add(name + "-moved", group, name);
                LockableResource unchanged = add(name + "-unchanged", group, name);
                RequiredResourcesProperty property = new RequiredResourcesProperty(
                        mode.equals("named") ? moved.getName() + " " + unchanged.getName() : null,
                        "DEVICE",
                        mode.equals("label") || mode.equals("script") ? "2" : null,
                        mode.equals("label") ? name : null,
                        mode.equals("script")
                                ? new SecureGroovyScript("return resourceLabels.contains('" + name + "')", true, null)
                                : null);
                if (!group.equals("common") || mode.equals("group")) {
                    property.setGroup("${GROUP}");
                }
                var job = j.createFreeStyleProject(name);
                job.addProperty(property);
                EnvVars env = new EnvVars("GROUP", group);
                LockableResourcesStruct requirement = new LockableResourcesStruct(property, env);
                if (mode.equals("named")) {
                    assertTrue(manager.queue(requirement.required, queueId, job.getFullName(), group));
                } else {
                    assertEquals(
                            List.of(moved, unchanged),
                            manager.tryQueue(requirement, queueId, job.getFullName(), 2, new HashMap<>(), LOGGER));
                }
                assertTrue(moved.isQueuedByTask(queueId));
                assertTrue(unchanged.isQueuedByTask(queueId));

                // Invoke the real handoff without allowing queue refresh to replace the admitted resources.
                moved.setGroup("changed");
                FreeStyleBuild build = mock(FreeStyleBuild.class);
                doReturn(job).when(build).getParent();
                ByteArrayOutputStream log = new ByteArrayOutputStream();
                try (StreamTaskListener listener = new StreamTaskListener(log, StandardCharsets.UTF_8)) {
                    doReturn(env).when(build).getEnvironment(listener);
                    new LockRunListener().onStarted(build, listener);
                }
                assertTrue(moved.isFree(), name);
                assertTrue(unchanged.isFree(), name);
                assertTrue(manager.getResourcesFromProject(job.getFullName()).isEmpty(), name);
                assertNull(build.getAction(ResourceVariableNameAction.class), name);
                verify(build).setResult(Result.FAILURE);
                verify(build).addAction(any(GroupLockFailureAction.class));
                assertTrue(log.toString(StandardCharsets.UTF_8).contains("failed to lock"), name);
                assertFalse(log.toString(StandardCharsets.UTF_8).contains("acquired lock"), name);
                unchanged.setGroup("changed");
                queueId++;
            }
        }
    }

    @Test
    void freestyleGroupMismatchStopsBuilders(JenkinsRule j) throws Exception {
        LockableResource resource = add("handoff-device", "production", "device");
        RequiredResourcesProperty property = new RequiredResourcesProperty(null, "DEVICE", "1", "device", null);
        property.setGroup("production");
        var job = j.createFreeStyleProject("freestyle-handoff");
        job.addProperty(property);
        AtomicBoolean builderExecuted = new AtomicBoolean();
        job.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
                builderExecuted.set(true);
                throw new AssertionError("Hardware builder must not run without its admitted resource");
            }
        });

        var listeners = RunListener.all();
        LockRunListener production = listeners.get(LockRunListener.class);
        assertNotNull(production);
        GroupHandoffListener handoff = new GroupHandoffListener(production);
        assertTrue(listeners.remove(production));
        FreeStyleBuild build;
        try {
            listeners.add(handoff);
            build = job.scheduleBuild2(0).get();
        } finally {
            listeners.remove(handoff);
            listeners.add(production);
        }
        j.assertBuildStatus(Result.FAILURE, build);
        assertTrue(handoff.wasQueued, "group must change after admission, before the locking listener");
        assertFalse(builderExecuted.get(), "group admission failure must veto hardware builder execution");
        assertTrue(resource.isFree());
        assertNull(build.getAction(ResourceVariableNameAction.class));
        j.assertLogContains("failed to lock", build);
    }

    private static class GroupHandoffListener extends RunListener<FreeStyleBuild> {
        private final LockRunListener production;
        private volatile boolean wasQueued;

        private GroupHandoffListener(LockRunListener production) {
            this.production = production;
        }

        @Override
        public void onStarted(FreeStyleBuild build, TaskListener listener) {
            if (!build.getParent().getName().equals("freestyle-handoff")) {
                return;
            }
            synchronized (LockableResourcesManager.syncResources) {
                LockableResource resource = LockableResourcesManager.get().fromName("handoff-device");
                wasQueued = resource.isQueuedByTask(build.getQueueId());
                resource.setGroup("changed");
            }
            production.onStarted(build, listener);
        }
    }

    @Test
    void remoteGroupOnlyExtraInheritsGroupForValidationAdmissionAndAllocation(JenkinsRule j) {
        LockableResource exposed = add("protected", "production", "exposed");
        LockableResource hidden = add("hidden", "production", "");
        LockableResource ordinary = add("ordinary", null, "exposed");
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("exposed");
        LockStep step = new LockStep(null);
        step.setGroup("production");
        LockStepResource inherited = new LockStepResource(null);
        inherited.setQuantity(1);
        step.setExtra(List.of(inherited));
        step.validate(false);
        RemoteLockRequest request = RemoteLockRequest.from(step);
        assertFalse(request.isGroupOnly(), "extra-only requests must not also acquire the entire main group");
        assertNull(request.getExtra().get(0).getGroup());
        RemoteResolver.validateAsLocalStep(request, false);
        RemoteResolver resolver = new RemoteResolver(manager);
        List<LockableResourcesStruct> structs = resolver.toRemoteStructs(request);
        assertEquals(1, structs.size());
        assertTrue(structs.get(0).isGroupOnly());
        assertEquals("production", structs.get(0).getGroup());
        assertEquals("1", structs.get(0).requiredNumber);
        RemoteLockRecord acquired = RemoteLockManager.get().enqueue(request, "client");
        assertEquals(RemoteLockState.ACQUIRED, acquired.getState());
        assertEquals(List.of("protected"), acquired.getAcquiredResourceNames());
        assertTrue(hidden.isFree());
        assertTrue(ordinary.isFree());
        RemoteLockManager.get().release(acquired.getLockId());
        assertTrue(exposed.isFree());

        RemoteLockRequest missingGroup = new RemoteLockRequest(
                null,
                null,
                0,
                null,
                false,
                "SEQUENTIAL",
                false,
                List.of(new RemoteLockRequest.ExtraResource(null, null, 1)),
                0,
                0,
                "MINUTES",
                null);
        assertThrows(IllegalArgumentException.class, () -> RemoteResolver.validateAsLocalStep(missingGroup, false));
        manager.setExposeLabel("");
        RemoteLockRecord unexposed = RemoteLockManager.get().enqueue(request, "client");
        assertEquals(RemoteLockState.FAILED, unexposed.getState());
        assertEquals("UNKNOWN_GROUP", unexposed.getErrorCode());
        assertTrue(exposed.isFree());
    }

    @Test
    void remoteGroupOnlyRemainsExposureScopedAndLegacyEmptyRequestRemainsNoop(JenkinsRule j) {
        add("protected", "production", "exposed");
        add("hidden", "production", "");
        add("ordinary", null, "exposed");
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("exposed");
        RemoteLockRecord record = RemoteLockManager.get()
                .enqueue(
                        new RemoteLockRequest(
                                null,
                                null,
                                0,
                                null,
                                false,
                                "SEQUENTIAL",
                                false,
                                null,
                                0,
                                0,
                                "MINUTES",
                                null,
                                "production"),
                        "client");
        assertEquals(RemoteLockState.ACQUIRED, record.getState());
        assertEquals(List.of("protected"), record.getAcquiredResourceNames());
        assertTrue(manager.fromName("hidden").isFree());
        assertTrue(manager.fromName("ordinary").isFree());
        manager.setAllowEmptyOrNullValues(true);
        RemoteLockRequest empty = RemoteLockRequest.from(new LockStep(null));
        assertFalse(empty.isGroupOnly());
        RemoteLockRecord noop = RemoteLockManager.get().enqueue(empty, "client");
        assertEquals(RemoteLockState.ACQUIRED, noop.getState());
        assertTrue(noop.getAcquiredResourceNames().isEmpty());
        RemoteLockManager.get().release(record.getLockId());
        RemoteLockManager.get().release(noop.getLockId());
    }

    private static LockableResource add(String name, String group, String labels) {
        LockableResource resource = new LockableResource(name);
        resource.setGroup(group);
        resource.setLabelsFromString(labels);
        LockableResourcesManager.get().addResource(resource);
        return resource;
    }

    private static LockableResourcesStruct selector(String group, int quantity) {
        LockableResourcesStruct requirement = new LockableResourcesStruct(List.of(), "device", quantity);
        requirement.setGroup(group);
        return requirement;
    }

    @Test
    void genericLabelCannotAcquireProtectedDeviceAndAllIsGroupScoped(JenkinsRule j) {
        LockableResource protectedDevice = add("protected", "production", "device");
        LockableResource first = add("first", null, "device");
        LockableResource second = add("second", "  ", "device");
        LockableResourcesManager manager = LockableResourcesManager.get();
        assertEquals("common", first.getGroup());
        assertEquals("common", second.getGroup());
        assertEquals(List.of(first, second), manager.getAvailableResources(List.of(selector(null, 0))));
        assertEquals(List.of(protectedDevice), manager.getAvailableResources(List.of(selector("production", 0))));
        manager.reserve(List.of(first, second), "test");
        assertNull(manager.getAvailableResources(List.of(selector(null, 1))));
        assertTrue(protectedDevice.isFree());
    }

    @Test
    void namedAndEmptyGroupValidationFailsClearly(JenkinsRule j) {
        add("protected", "production", "device");
        LockStep named = new LockStep("protected");
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class, () -> named.validate(false));
        assertTrue(mismatch.getMessage().contains("production"));
        assertTrue(mismatch.getMessage().contains("common"));
        named.setGroup("production");
        named.validate(false);
        LockStep label = new LockStep(null);
        label.setLabel("device");
        assertThrows(IllegalArgumentException.class, () -> label.validate(false));
        label.setGroup("missing");
        assertThrows(IllegalArgumentException.class, () -> label.validate(false));
        label.setGroup("production");
        label.validate(false);
        LockStep nonexistent = new LockStep("missing-name");
        nonexistent.setGroup("production");
        assertThrows(IllegalArgumentException.class, () -> nonexistent.validate(false));
        assertNull(LockableResourcesManager.get().fromName("missing-name"));
    }

    @Test
    void extraInheritsMainGroupUnlessExplicitlyOverridden(JenkinsRule j) {
        add("protected", "production", "device");
        add("ordinary", null, "device");
        LockStep step = new LockStep(null);
        step.setGroup("production");
        LockStepResource inherited = new LockStepResource("protected");
        LockStepResource overridden = new LockStepResource("ordinary");
        overridden.setGroup("common");
        step.setExtra(List.of(inherited, overridden));
        step.validate(false);
        assertEquals("production", step.getResources().get(0).getGroup());
        assertEquals("common", step.getResources().get(1).getGroup());
        assertNull(inherited.getGroup(), "resolution must not mutate DSL extras");
        RemoteLockRequest remote = RemoteLockRequest.from(step);
        assertEquals("production", remote.getGroup());
        assertNull(remote.getExtra().get(0).getGroup());
        assertEquals("common", remote.getExtra().get(1).getGroup());
        List<LockableResourcesStruct> structs =
                new RemoteResolver(LockableResourcesManager.get()).toRemoteStructs(remote);
        assertEquals("production", structs.get(0).getGroup());
        assertEquals("common", structs.get(1).getGroup());
    }

    @Test
    void pipelineWaitsInGroupAndRetriesWithoutFallback(JenkinsRule j) throws Exception {
        LockableResource protectedDevice = add("protected", "production", "device");
        LockableResource ordinary = add("ordinary", null, "device");
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.reserve(List.of(protectedDevice), "test");
        WorkflowJob job = j.createProject(WorkflowJob.class, "group-pipeline");
        job.setDefinition(new CpsFlowDefinition("""
                lock(label: 'device', group: 'production', quantity: 1, variable: 'DEVICE') {
                  echo "selected=${env.DEVICE}"
                  semaphore 'group-acquired'
                }
                """, true));
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is reserved", run);
        assertTrue(ordinary.isFree());
        assertFalse(protectedDevice.isLocked());
        manager.unreserve(List.of(protectedDevice));
        SemaphoreStep.waitForStart("group-acquired/1", run);
        j.assertLogContains("selected=protected", run);
        assertTrue(ordinary.isFree());
        SemaphoreStep.success("group-acquired/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(run));
    }

    @Test
    void pipelineDefaultGroupRejectsProtectedName(JenkinsRule j) throws Exception {
        add("protected", "production", "device");
        WorkflowJob job = j.createProject(WorkflowJob.class, "group-rejected");
        job.setDefinition(new CpsFlowDefinition("lock('protected') { error 'body must not run' }", true));
        WorkflowRun run = job.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, run);
        j.assertLogContains("not requested group", run);
        assertTrue(LockableResourcesManager.get().fromName("protected").isFree());
    }

    @Test
    void freestyleBothAllocationPathsEnforceGroupAndExpandParameters(JenkinsRule j) throws Exception {
        LockableResource protectedDevice = add("protected", "production", "device");
        LockableResource ordinary = add("ordinary", null, "device");
        LockableResourcesManager manager = LockableResourcesManager.get();
        RequiredResourcesProperty property = new RequiredResourcesProperty(null, null, "1", "device", null);
        property.setGroup("${GROUP}");
        LockableResourcesStruct requirement = new LockableResourcesStruct(property, new EnvVars("GROUP", "production"));
        manager.reserve(List.of(protectedDevice), "test");
        assertNull(manager.tryQueue(requirement, 101, "group-job", 1, Map.of(), LOGGER));
        assertTrue(ordinary.isFree());
        manager.unreserve(List.of(protectedDevice));
        assertEquals(List.of(protectedDevice), manager.tryQueue(requirement, 101, "group-job", 1, Map.of(), LOGGER));
        assertThrows(IllegalArgumentException.class, () -> manager.queue(List.of(protectedDevice), 102, "other"));
        protectedDevice.unqueue();
        assertTrue(manager.queue(List.of(protectedDevice), 102, "other", "production"));
        protectedDevice.unqueue();
        LockableResourcesStruct wrong = selector("unknown", 1);
        assertThrows(ExecutionException.class, () -> manager.tryQueue(wrong, 103, "wrong", 1, Map.of(), LOGGER));
        RequiredResourcesProperty named = new RequiredResourcesProperty("protected", null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> new LockableResourcesStruct(named, new EnvVars()));
        named.setGroup("production");
        var job = j.createFreeStyleProject("named-group");
        job.addProperty(named);
        j.configRoundtrip(job);
        assertEquals(
                "production", job.getProperty(RequiredResourcesProperty.class).getGroup());
        j.buildAndAssertSuccess(job);
    }

    @Test
    void remoteExposureAndGroupIntersectForAdmissionAllocationAndRetry(JenkinsRule j) {
        LockableResource protectedDevice = add("protected", "production", "device exposed");
        LockableResource ordinary = add("ordinary", null, "device exposed");
        LockableResource hidden = add("hidden", "production", "device");
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRemoteApiEnabled(true);
        manager.setExposeLabel("exposed");
        RemoteLockManager remote = RemoteLockManager.get();
        RemoteLockRequest production = new RemoteLockRequest(
                null, "device", 0, null, false, "SEQUENTIAL", false, null, 0, 0, "MINUTES", null, "production");
        RemoteLockRecord first = remote.enqueue(production, "client");
        assertEquals(RemoteLockState.ACQUIRED, first.getState());
        assertEquals(List.of("protected"), first.getAcquiredResourceNames());
        RemoteLockRecord waiting = remote.enqueue(production, "client");
        assertEquals(RemoteLockState.QUEUED, waiting.getState());
        assertTrue(ordinary.isFree());
        assertTrue(hidden.isFree());
        remote.release(first.getLockId());
        assertEquals(RemoteLockState.ACQUIRED, waiting.getState());
        assertEquals(List.of("protected"), waiting.getAcquiredResourceNames());
        RemoteLockRecord generic = remote.enqueue(
                new RemoteLockRequest(null, "device", 0, null, false, "SEQUENTIAL", false, null, 0, 0, "MINUTES", null),
                "client");
        assertEquals(List.of("ordinary"), generic.getAcquiredResourceNames());
        assertThrows(
                IllegalArgumentException.class,
                () -> remote.enqueue(
                        new RemoteLockRequest(
                                "protected", null, 0, null, false, "SEQUENTIAL", false, null, 0, 0, "MINUTES", null),
                        "client"));
        RemoteLockRecord missing = remote.enqueue(
                new RemoteLockRequest(
                        null, "device", 1, null, false, "SEQUENTIAL", false, null, 0, 0, "MINUTES", null, "missing"),
                "client");
        assertEquals(RemoteLockState.FAILED, missing.getState());
        remote.release(waiting.getLockId());
        remote.release(generic.getLockId());
        assertTrue(protectedDevice.isFree());
    }
}
