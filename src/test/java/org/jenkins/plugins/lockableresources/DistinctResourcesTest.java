/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@Issue("961")
class DistinctResourcesTest extends LockStepTestBase {
    private LockableResourcesManager devices() {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.createResourceWithLabel("device1", "device special");
        manager.createResourceWithLabel("device2", "device");
        manager.setRequireDistinctResources(true);
        return manager;
    }

    private LockableResourcesStruct label(String expression, int quantity) {
        return new LockableResourcesStruct(null, expression, quantity);
    }

    private LockableResourcesStruct named(String name) {
        return new LockableResourcesStruct(List.of(name));
    }

    @Test
    void legacyReuseIsUnchangedByDefault(JenkinsRule j) {
        LockableResourcesManager manager = devices();
        manager.setRequireDistinctResources(false);
        assertFalse(manager.isRequireDistinctResources());
        assertEquals(
                List.of("device1"),
                LockableResourcesManager.getResourcesNames(
                        manager.getAvailableResources(List.of(named("device1"), label("device", 1)))));
    }

    @Test
    void namedAndLabelRequestsUseDifferentDevicesInEitherOrder(JenkinsRule j) {
        LockableResourcesManager manager = devices();
        assertEquals(
                List.of("device1", "device2"),
                LockableResourcesManager.getResourcesNames(
                        manager.getAvailableResources(List.of(named("device1"), label("device", 1)))));
        assertEquals(
                List.of("device2", "device1"),
                LockableResourcesManager.getResourcesNames(
                        manager.getAvailableResources(List.of(label("device", 1), named("device1")))));
    }

    @Test
    void broadLabelDoesNotConsumeOnlySpecificDevice(JenkinsRule j) {
        LockableResourcesManager manager = devices();
        assertEquals(
                List.of("device2", "device1"),
                LockableResourcesManager.getResourcesNames(
                        manager.getAvailableResources(List.of(label("device", 1), label("device && special", 1)))));
    }

    @Test
    void equallyConstrainedLabelsCanRequireMultipleReassignments(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.setRequireDistinctResources(true);
        manager.createResourceWithLabel("r1", "A D");
        manager.createResourceWithLabel("r2", "A B D");
        manager.createResourceWithLabel("r3", "B C");
        manager.createResourceWithLabel("r4", "C");
        assertEquals(
                List.of("r1", "r3", "r4", "r2"),
                LockableResourcesManager.getResourcesNames(manager.getAvailableResources(
                        List.of(label("A", 1), label("B", 1), label("C", 1), label("D", 1)))));
    }

    @Test
    void quantitiesRequireDistinctDevicesAcrossEntries(JenkinsRule j) {
        LockableResourcesManager manager = devices();
        manager.createResourceWithLabel("device3", "device");
        List<LockableResource> result =
                manager.getAvailableResources(List.of(label("device", 2), label("device && special", 1)));
        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals(3, new HashSet<>(result).size());
        assertEquals("device1", result.get(2).getName());
    }

    @Test
    void randomStrategyStillProducesACompleteMatching(JenkinsRule j) {
        LockableResourcesManager manager = devices();
        for (int i = 0; i < 20; i++) {
            assertEquals(
                    List.of("device2", "device1"),
                    LockableResourcesManager.getResourcesNames(manager.getAvailableResources(
                            List.of(label("device", 1), label("device && special", 1)),
                            null,
                            ResourceSelectStrategy.RANDOM)));
        }
    }

    @Test
    void insufficientUnionAndRepeatedNamesCannotProducePartialAllocation(JenkinsRule j) {
        LockableResourcesManager manager = devices();
        assertNull(manager.getAvailableResources(List.of(label("device", 1), label("device", 2))));
        assertNull(manager.getAvailableResources(List.of(named("device1"), named("device1"))));
        assertTrue(manager.fromName("device1").isFree());
        assertTrue(manager.fromName("device2").isFree());
    }

    @Test
    void allQuantityUsesVisiblePoolAndCannotOverlapAnotherEntry(JenkinsRule j) {
        LockableResourcesManager manager = devices();
        assertEquals(
                List.of("device1", "device2"),
                LockableResourcesManager.getResourcesNames(manager.getAvailableResources(List.of(label("device", 0)))));
        assertNull(manager.getAvailableResources(List.of(label("device", 0), named("device1"))));
        assertEquals(
                List.of("device2"),
                LockableResourcesManager.getResourcesNames(manager.getAvailableResources(
                        List.of(label("device", 0)),
                        null,
                        null,
                        r -> r.getName().equals("device2"))));
        assertNull(manager.getAvailableResources(
                List.of(named("device1")), null, null, r -> r.getName().equals("device2")));
        manager.fromName("device1").setReservedBy("maintenance");
        assertNull(manager.getAvailableResources(List.of(label("device", 0))));
        assertNull(manager.getAvailableResources(List.of(label("device && special", 1))));
    }

    @Test
    void queuedRequestIsRematchedWhenSpecificDeviceIsReleased(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = devices();
        WorkflowJob holder = j.jenkins.createProject(WorkflowJob.class, "holder");
        holder.setDefinition(new CpsFlowDefinition("""
                lock('device1') {
                    semaphore 'hold'
                }
                """, true));
        WorkflowRun first = holder.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hold/1", first);

        WorkflowJob request = j.jenkins.createProject(WorkflowJob.class, "request");
        request.setDefinition(new CpsFlowDefinition("""
                lock(label: 'device', quantity: 1, variable: 'DEVICES',
                     extra: [[label: 'device && special', quantity: 1]]) {
                    echo "Devices: ${env.DEVICES}"
                }
                """, true));
        WorkflowRun second = request.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is not free, waiting for execution", second);
        isPaused(second, 1, 1);
        assertTrue(manager.fromName("device2").isFree());
        SemaphoreStep.success("hold/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(first));
        j.assertBuildStatusSuccess(j.waitForCompletion(second));
        j.assertLogContains("Devices: device2,device1", second);
        j.assertLogNotContains("Extra filter tries to allocate pre-reserved resources", second);
    }

    @Test
    void skipIfLockedDoesNotExecuteWithTooFewDistinctDevices(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = devices();
        WorkflowJob request = j.jenkins.createProject(WorkflowJob.class, "request");
        request.setDefinition(new CpsFlowDefinition("""
                lock(label: 'device', quantity: 2, skipIfLocked: true,
                     extra: [[label: 'device', quantity: 1]]) {
                    echo 'INSIDE LOCK'
                }
                echo 'Finished'
                """, true));
        WorkflowRun run = j.buildAndAssertSuccess(request);
        j.assertLogNotContains("INSIDE LOCK", run);
        j.assertLogContains("Finished", run);
        assertTrue(manager.fromName("device1").isFree());
        assertTrue(manager.fromName("device2").isFree());
    }

    @Test
    void configurationRoundTripPersistsOptIn(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        assertFalse(manager.isRequireDistinctResources());
        manager.setRequireDistinctResources(true);
        j.configRoundtrip();
        assertTrue(manager.isRequireDistinctResources());
    }
}
