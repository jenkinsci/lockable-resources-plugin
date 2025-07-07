package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class NodesMirrorTest {

    private static final Logger LOGGER =
            Logger.getLogger(org.jenkins.plugins.lockableresources.NodesMirror.class.getName());

    @Test
    void mirror_few_nodes(JenkinsRule j) throws Exception {
        System.setProperty(Constants.SYSTEM_PROPERTY_ENABLE_NODE_MIRROR, "true");

        LOGGER.info("add agent: FirstAgent");
        j.createSlave("FirstAgent", "label label2", null);
        LOGGER.info("add agent: SecondAgent");
        j.createSlave("SecondAgent", null, null);

        // this is asynchronous operation, so wait until resources are created.
        LOGGER.info("wait for resources");
        for (int i = 1;
                !LockableResourcesManager.get().resourceExist("FirstAgent")
                        && !LockableResourcesManager.get().resourceExist("SecondAgent")
                        && i <= 10;
                i++) {
            Thread.sleep(100);
        }

        LOGGER.info("check agent: FirstAgent");
        LockableResource firstAgent = LockableResourcesManager.get().fromName("FirstAgent");

        assertEquals("FirstAgent", firstAgent.getName());
        // ! jenkins add always the node name as a label
        assertEquals("FirstAgent label label2", firstAgent.getLabels());

        LockableResource secondAgent = LockableResourcesManager.get().fromName("SecondAgent");
        assertEquals("SecondAgent", secondAgent.getName());
        assertEquals("SecondAgent", secondAgent.getLabels());

        // delete agent
        j.jenkins.removeNode(j.jenkins.getNode("FirstAgent"));

        for (int i = 1; LockableResourcesManager.get().fromName("FirstAgent") == null && i <= 10; i++) {
            Thread.sleep(100);
        }
        assertNull(LockableResourcesManager.get().fromName("FirstAgent"));
        assertNotNull(LockableResourcesManager.get().fromName("SecondAgent"));
    }

    @Test
    void mirror_locked_nodes(JenkinsRule j) throws Exception {
        System.setProperty(Constants.SYSTEM_PROPERTY_ENABLE_NODE_MIRROR, "true");

        j.createSlave("FirstAgent", "label label2", null);
        // this is asynchronous operation, so wait until resources has been created.
        for (int i = 1; LockableResourcesManager.get().fromName("FirstAgent") != null && i <= 10; i++) {
            Thread.sleep(100);
        }
        assertNotNull(LockableResourcesManager.get().fromName("FirstAgent"));

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                lock(label: 'label && label2', variable : 'lockedNode') {
                 echo 'wait for node: ' + env.lockedNode
                    semaphore 'wait-inside'
                }
                echo 'Finish'""",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("wait for node: FirstAgent", b1);
        SemaphoreStep.waitForStart("wait-inside/1", b1);
        j.jenkins.removeNode(j.jenkins.getNode("FirstAgent"));
        SemaphoreStep.success("wait-inside/1", null);
        // this resource is not removed, because it was locked.
        Thread.sleep(1000);
        assertNotNull(LockableResourcesManager.get().fromName("FirstAgent"));
    }
}
