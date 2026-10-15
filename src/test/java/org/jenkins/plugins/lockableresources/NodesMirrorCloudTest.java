package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.Node;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import java.time.Duration;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.jenkinci.plugins.mock_slave.MockCloud;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Node mirroring for agents provisioned and removed by a cloud. */
@WithJenkins
class NodesMirrorCloudTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @BeforeEach
    void enableNodeMirror() {
        System.setProperty(Constants.SYSTEM_PROPERTY_ENABLE_NODE_MIRROR, "true");
    }

    @AfterEach
    void disableNodeMirror() {
        System.clearProperty(Constants.SYSTEM_PROPERTY_ENABLE_NODE_MIRROR);
    }

    @Test
    void mirror_one_shot_cloud_agent(JenkinsRule j) throws Exception {
        addMockCloud(j, true);

        WorkflowRun b = startBuildOnCloudAgent(j);
        Node agent = getCloudAgent(j);
        assertNodeResource(agent);

        SemaphoreStep.success("wait/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b));

        // the one-shot retention strategy removes the agent once the build is done
        waitUntil(() -> j.jenkins.getNode(agent.getNodeName()) == null, "agent removed");
        waitUntil(() -> LockableResourcesManager.get().fromName(agent.getNodeName()) == null, "resource removed");
    }

    @Test
    void mirror_idle_cloud_agent(JenkinsRule j) throws Exception {
        addMockCloud(j, false);

        WorkflowRun b = startBuildOnCloudAgent(j);
        Node agent = getCloudAgent(j);
        assertNodeResource(agent);

        // MockCloud keeps idle agents for 1 minute and Jenkins checks retention once a minute.
        // Use the shortest idle retention (0 minutes) and run the check ourselves to keep the test fast.
        CloudRetentionStrategy retention = new CloudRetentionStrategy(0);
        ((AbstractCloudSlave) agent).setRetentionStrategy(retention);
        AbstractCloudComputer<?> computer = (AbstractCloudComputer<?>) agent.toComputer();
        assertNotNull(computer);

        SemaphoreStep.success("wait/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b));

        // the agent is still there until the retention strategy removes it
        assertNotNull(LockableResourcesManager.get().fromName(agent.getNodeName()));

        waitUntil(
                () -> {
                    retention.check(computer);
                    return j.jenkins.getNode(agent.getNodeName()) == null;
                },
                "idle agent removed");
        waitUntil(() -> LockableResourcesManager.get().fromName(agent.getNodeName()) == null, "resource removed");
    }

    private static void addMockCloud(JenkinsRule j, boolean oneShot) {
        MockCloud cloud = new MockCloud("mock");
        cloud.setLabels("mock");
        cloud.setOneShot(oneShot);
        j.jenkins.clouds.add(cloud);
    }

    private static WorkflowRun startBuildOnCloudAgent(JenkinsRule j) throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node('mock') { semaphore 'wait' }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait/1", b);
        return b;
    }

    private static Node getCloudAgent(JenkinsRule j) {
        Set<Node> agents = j.jenkins.getLabel("mock").getNodes();
        assertEquals(1, agents.size(), "cloud agents: " + agents);
        return agents.iterator().next();
    }

    private static void assertNodeResource(Node agent) throws Exception {
        // the resource is created asynchronously, when the agent is added
        waitUntil(() -> LockableResourcesManager.get().fromName(agent.getNodeName()) != null, "resource created");
        LockableResource resource = LockableResourcesManager.get().fromName(agent.getNodeName());

        assertTrue(resource.isNodeResource());
        Set<String> expectedLabels =
                agent.getAssignedLabels().stream().map(Object::toString).collect(Collectors.toSet());
        // ! jenkins add always the node name as a label
        assertTrue(expectedLabels.contains("mock"), "agent labels: " + expectedLabels);
        assertTrue(expectedLabels.contains(agent.getNodeName()), "agent labels: " + expectedLabels);
        assertEquals(expectedLabels, Set.copyOf(resource.getLabelsAsList()));
    }

    private static void waitUntil(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for: " + description);
            }
            Thread.sleep(100);
        }
    }
}
