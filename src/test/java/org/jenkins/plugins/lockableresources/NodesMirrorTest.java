package org.jenkins.plugins.lockableresources;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class NodesMirrorTest {

  @Rule public final JenkinsRule j = new JenkinsRule();

  @Test
  public void mirror_few_nodes() throws Exception {
    System.setProperty(Constants.SYSTEM_PROPERTY_ENABLE_NODE_MIRROR, "true");

    j.createSlave("FirstAgent", "label label2", null);
    j.createSlave("SecondAgent", null, null);

    // this is asynchronous operation, so wait until resources are created.
    for(int i = 1; LockableResourcesManager.get().fromName("SecondAgent") != null && i <= 10; i++) {
      Thread.sleep(100);
    }

    LockableResource firstAgent = LockableResourcesManager.get().fromName("FirstAgent");

    assertEquals("FirstAgent", firstAgent.getName());
    // ! jenkins add always the node name as a label
    assertEquals("FirstAgent label label2", firstAgent.getLabels());

    LockableResource secondAgent = LockableResourcesManager.get().fromName("SecondAgent");
    assertEquals("SecondAgent", secondAgent.getName());
    assertEquals("SecondAgent", secondAgent.getLabels());

    // delete agent
    j.jenkins.removeNode(j.jenkins.getNode("FirstAgent"));

    for(int i = 1; LockableResourcesManager.get().fromName("FirstAgent") == null && i <= 10; i++) {
      Thread.sleep(100);
    }
    assertNull(LockableResourcesManager.get().fromName("FirstAgent"));
    assertNotNull(LockableResourcesManager.get().fromName("SecondAgent"));
  }

  @Test
  public void mirror_locked_nodes() throws Exception {
    System.setProperty("org.jenkins.plugins.lockableresources.ENABLE_NODE_MIRROR", "true");

    j.createSlave("FirstAgent", "label label2", null);
    // this is asynchronous operation, so wait until resources has been created.
    for(int i = 1; LockableResourcesManager.get().fromName("FirstAgent") != null && i <= 10; i++) {
      Thread.sleep(100);
    }
    assertNotNull(LockableResourcesManager.get().fromName("FirstAgent"));

    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
      new CpsFlowDefinition(
        "lock(label: 'label && label2', variable : 'lockedNode') {\n"
          + " echo 'wait for node: ' + env.lockedNode\n"
          + "	semaphore 'wait-inside'\n"
          + "}\n"
          + "echo 'Finish'",
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
