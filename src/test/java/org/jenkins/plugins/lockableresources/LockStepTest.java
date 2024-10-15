package org.jenkins.plugins.lockableresources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.common.collect.ImmutableMap;
import hudson.model.Result;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.logging.Logger;
import net.sf.json.JSONObject;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.WithPlugin;

public class LockStepTest extends LockStepTestBase {

    private static final Logger LOGGER = Logger.getLogger(LockStepTest.class.getName());

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void autoCreateResource() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock('resource1') {\n" + "	echo 'Resource locked'\n" + "}\n" + "echo 'Finish'", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogContains("Resource [resource1] did not exist. Created.", b1);

        assertNull(LockableResourcesManager.get().fromName("resource1"));
    }

    @Test
    public void lockNothing() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(
                new CpsFlowDefinition("lock() {\n" + "  echo 'Nothing locked.'\n" + "}\n" + "echo 'Finish'", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b1));
        j.assertLogContains("Either resource label or resource name must be specified", b1);
    }

    @Test
    public void lockWithLabel() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', variable: 'var') {\n"
                        + "	echo \"Resource locked: ${env.var}\"\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogContains("Lock released on resource [Label: label1]", b1);
        j.assertLogContains("Resource locked: resource1", b1);
        isPaused(b1, 1, 0);

        assertNotNull(LockableResourcesManager.get().fromName("resource1"));
    }

    @Test
    public void lockRandomWithLabel() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', variable: 'var', resourceSelectStrategy: 'random') {\n"
                        + "	echo \"Resource locked: ${env.var}\"\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogContains("Lock released on resource [Label: label1]", b1);
        j.assertLogContains("Resource locked: resource1", b1);
        isPaused(b1, 1, 0);

        assertNotNull(LockableResourcesManager.get().fromName("resource1"));
    }

    @Test
    public void lockOrderLabel() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
        LockableResourcesManager.get().createResourceWithLabel("resource3", "label1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', quantity: 2) {\n" + "	semaphore 'wait-inside'\n" + "}\n" + "echo 'Finish'",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
        // Ensure that b2 reaches the lock before b3
        j.waitForMessage("[Label: label1, Quantity: 2] is not free, waiting for execution ...", b2);
        j.waitForMessage("Found 1 available resource(s). Waiting for correct amount: 2.", b2);
        isPaused(b2, 1, 1);
        WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
        // Both 2 and 3 are waiting for locking Label: label1, Quantity: 2
        j.waitForMessage("[Label: label1, Quantity: 2] is not free, waiting for execution ...", b3);
        j.waitForMessage("Found 1 available resource(s). Waiting for correct amount: 2.", b3);
        isPaused(b3, 1, 1);

        // Unlock Label: label1, Quantity: 2
        SemaphoreStep.success("wait-inside/1", null);
        j.waitForMessage("Lock released on resource [Label: label1, Quantity: 2]", b1);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        isPaused(b1, 1, 0);

        // #2 gets the lock before #3 (in the order as they requested the lock)
        j.waitForMessage("Lock acquired on [Label: label1, Quantity: 2]", b2);
        SemaphoreStep.success("wait-inside/2", null);
        j.waitForMessage("Finish", b2);
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        isPaused(b2, 1, 0);
        j.waitForMessage("Lock acquired on [Label: label1, Quantity: 2]", b3);
        SemaphoreStep.success("wait-inside/3", null);
        j.waitForMessage("Finish", b3);
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        isPaused(b3, 1, 0);

        assertNotNull(LockableResourcesManager.get().fromName("resource1"));
        assertNotNull(LockableResourcesManager.get().fromName("resource2"));
        assertNotNull(LockableResourcesManager.get().fromName("resource3"));
    }

    @Test
    public void lockOrderLabelQuantity() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
        LockableResourcesManager.get().createResourceWithLabel("resource3", "label1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', quantity: 2) {\n" + "	semaphore 'wait-inside'\n" + "}\n" + "echo 'Finish'",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
        // Ensure that b2 reaches the lock before b3
        j.waitForMessage("[Label: label1, Quantity: 2] is not free, waiting for execution ...", b2);
        j.waitForMessage("Found 1 available resource(s). Waiting for correct amount: 2.", b2);
        isPaused(b2, 1, 1);

        WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
        p3.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', quantity: 1) {\n"
                        + "	semaphore 'wait-inside-quantity1'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
        // While 2 continues waiting, 3 can continue directly
        SemaphoreStep.waitForStart("wait-inside-quantity1/1", b3);
        // Let 3 finish
        SemaphoreStep.success("wait-inside-quantity1/1", null);
        j.waitForMessage("Finish", b3);
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        isPaused(b3, 1, 0);

        // Unlock Label: label1, Quantity: 2
        SemaphoreStep.success("wait-inside/1", null);
        j.waitForMessage("Lock released on resource [Label: label1, Quantity: 2]", b1);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        isPaused(b1, 1, 0);

        // #2 gets the lock before #3 (in the order as they requested the lock)
        j.waitForMessage("Lock acquired on [Label: label1, Quantity: 2]", b2);
        SemaphoreStep.success("wait-inside/2", null);
        j.waitForMessage("Finish", b2);
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        isPaused(b2, 1, 0);

        assertNotNull(LockableResourcesManager.get().fromName("resource1"));
        assertNotNull(LockableResourcesManager.get().fromName("resource2"));
        assertNotNull(LockableResourcesManager.get().fromName("resource3"));
    }

    @Test
    public void lockOrderLabelQuantityFreedResources() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
        LockableResourcesManager.get().createResourceWithLabel("resource3", "label1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1') {\n" + "	semaphore 'wait-inside'\n" + "}\n" + "echo 'Finish'", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        JSONObject apiRes = TestHelpers.getResourceFromApi(j, "resource1", true);
        assertThat(apiRes, hasEntry("labels", "label1"));

        WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
        p2.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', quantity: 2) {\n"
                        + "	semaphore 'wait-inside-quantity2'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        // Ensure that b2 reaches the lock before b3
        j.waitForMessage("[Label: label1, Quantity: 2] is not free, waiting for execution ...", b2);
        j.waitForMessage("Found 0 available resource(s). Waiting for correct amount: 2.", b2);
        isPaused(b2, 1, 1);

        WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
        p3.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', quantity: 1) {\n"
                        + "	semaphore 'wait-inside-quantity1'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Label: label1, Quantity: 1] is not free, waiting for execution ...", b3);
        j.waitForMessage("Found 0 available resource(s). Waiting for correct amount: 1.", b3);
        isPaused(b3, 1, 1);

        // Unlock Label: label1
        SemaphoreStep.success("wait-inside/1", null);
        j.waitForMessage("Lock released on resource [Label: label1]", b1);
        isPaused(b1, 1, 0);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        // Both get their lock
        j.waitForMessage("Lock acquired on [Label: label1, Quantity: 2]", b2);
        j.waitForMessage("Lock acquired on [Label: label1, Quantity: 1]", b3);

        SemaphoreStep.success("wait-inside-quantity2/1", null);
        SemaphoreStep.success("wait-inside-quantity1/1", null);
        j.waitForMessage("Finish", b2);
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        j.waitForMessage("Finish", b3);
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        isPaused(b2, 1, 0);
        isPaused(b3, 1, 0);

        assertNotNull(LockableResourcesManager.get().fromName("resource1"));
        assertNotNull(LockableResourcesManager.get().fromName("resource2"));
        assertNotNull(LockableResourcesManager.get().fromName("resource3"));
    }

    @Test
    public void lockOrder() throws Exception {
        LockableResourcesManager.get().createResource("resource1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock('resource1') {\n" + "	semaphore 'wait-inside'\n" + "}\n" + "echo 'Finish'", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
        // Ensure that b2 reaches the lock before b3
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b2);
        isPaused(b2, 1, 1);
        WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
        // Both 2 and 3 are waiting for locking resource1

        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b3);
        isPaused(b3, 1, 1);

        // Unlock resource1
        SemaphoreStep.success("wait-inside/1", null);
        j.waitForMessage("Lock released on resource [Resource: resource1]", b1);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        isPaused(b1, 1, 0);

        // #2 gets the lock before #3 (in the order as they requested the lock)
        j.waitForMessage("Trying to acquire lock on [Resource: resource1]", b2);
        SemaphoreStep.success("wait-inside/2", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        isPaused(b2, 1, 0);
        j.waitForMessage("Trying to acquire lock on [Resource: resource1]", b3);
        SemaphoreStep.success("wait-inside/3", null);
        j.waitForMessage("Finish", b3);
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        isPaused(b3, 1, 0);
    }

    @Test
    public void lockInverseOrder() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(resource: 'resource1', inversePrecedence: true) {\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
        // Ensure that b2 reaches the lock before b3
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b2);
        isPaused(b2, 1, 1);
        WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
        // Both 2 and 3 are waiting for locking resource1

        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b3);
        isPaused(b3, 1, 1);

        // Unlock resource1
        SemaphoreStep.success("wait-inside/1", null);
        j.waitForMessage("Lock released on resource [Resource: resource1]", b1);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        isPaused(b1, 1, 0);

        // #3 gets the lock before #2 because of inversePrecedence
        j.waitForMessage("Trying to acquire lock on [Resource: resource1]", b3);
        SemaphoreStep.success("wait-inside/2", null);
        isPaused(b3, 1, 0);
        j.waitForMessage("Trying to acquire lock on [Resource: resource1]", b2);
        SemaphoreStep.success("wait-inside/3", null);
        j.waitForMessage("Finish", b3);
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        isPaused(b2, 1, 0);
    }

    @Test
    /*
      start time | job | resource  | inversePrecedence
      ------     |---  |---        |---
      00:01      | j1  | resource1 | false
      00:02      | j2  | resource1 | false
      00:03      | j3  | resource1 | **true**
      00:04      | j4  | resource1 | false
      00:05      | j5  | resource1 | **true**
      00:06      | j6  | resource1 | false

      expected lock order: j1 -> j5 -> j3 -> j2 -> j4 -> j6
    */
    @Issue("GITHUB-560")
    public void lockInverseOrder2() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");

        /* *****
        00:01      | j1  | resource1 | false
        */
        p.setDefinition(new CpsFlowDefinition(
                "lock(resource: 'resource1', inversePrecedence: false) {\n"
                        + "     echo 'locked 1'\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        /* *****
        00:02      | j2  | resource1 | false
        */
        p.setDefinition(new CpsFlowDefinition(
                "lock(resource: 'resource1', inversePrecedence: false) {\n"
                        + "     echo 'locked 2'\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
        // Ensure that b2 reaches the lock before b3
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b2);

        /* *****
        00:03      | j3  | resource1 | true
        */
        p.setDefinition(new CpsFlowDefinition(
                "lock(resource: 'resource1', inversePrecedence: true) {\n"
                        + "     echo 'locked 3'\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
        // Ensure that b3 reaches the lock before b4
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b3);

        /* *****
        00:04      | j4  | resource1 | false
        */
        p.setDefinition(new CpsFlowDefinition(
                "lock(resource: 'resource1', inversePrecedence: false) {\n"
                        + "     echo 'locked 4'\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b4 = p.scheduleBuild2(0).waitForStart();
        // Ensure that b4 reaches the lock before b4
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b4);

        /* *****
        00:05      | j5  | resource1 | true
        */
        p.setDefinition(new CpsFlowDefinition(
                "lock(resource: 'resource1', inversePrecedence: true) {\n"
                        + "     echo 'locked 5'\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b5 = p.scheduleBuild2(0).waitForStart();
        // Ensure that b5 reaches the lock before b6
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b5);

        /* *****
        00:06      | j6  | resource1 | false
        */
        p.setDefinition(new CpsFlowDefinition(
                "lock(resource: 'resource1', inversePrecedence: false) {\n"
                        + "     echo 'locked 6'\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b6 = p.scheduleBuild2(0).waitForStart();
        // Ensure that b6 reaches the lock
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b6);

        // check logs
        j.assertLogContains("locked 1", b1);
        j.assertLogNotContains("locked 2", b2);
        j.assertLogNotContains("locked 3", b3);
        j.assertLogNotContains("locked 4", b4);
        j.assertLogNotContains("locked 5", b5);
        j.assertLogNotContains("locked 6", b6);

        // release first lock (on build 1)
        SemaphoreStep.success("wait-inside/1", null);
        j.waitForMessage("Lock released on resource", b1);

        SemaphoreStep.waitForStart("wait-inside/2", b5);
        j.assertLogNotContains("locked 2", b2);
        j.assertLogNotContains("locked 3", b3);
        j.assertLogNotContains("locked 4", b4);
        j.assertLogContains("locked 5", b5);
        j.assertLogNotContains("locked 6", b6);

        // release 2. lock (on build 5)
        SemaphoreStep.success("wait-inside/2", null);
        j.waitForMessage("Lock released on resource", b5);

        SemaphoreStep.waitForStart("wait-inside/3", b3);
        j.assertLogNotContains("locked 2", b2);
        j.assertLogContains("locked 3", b3);
        j.assertLogNotContains("locked 4", b4);
        j.assertLogNotContains("locked 5", b6);

        // release 3. lock (on build 3)
        SemaphoreStep.success("wait-inside/3", null);
        j.waitForMessage("Lock released on resource", b3);

        SemaphoreStep.waitForStart("wait-inside/4", b2);
        j.assertLogContains("locked 2", b2);
        j.assertLogNotContains("locked 4", b4);
        j.assertLogNotContains("locked 6", b6);

        // release 4. lock (on build 2)
        SemaphoreStep.success("wait-inside/4", null);
        j.waitForMessage("Lock released on resource", b2);

        SemaphoreStep.waitForStart("wait-inside/5", b4);
        j.assertLogContains("locked 4", b4);
        j.assertLogNotContains("locked 6", b6);

        // release 5. lock (on build 4)
        SemaphoreStep.success("wait-inside/5", null);
        j.waitForMessage("Lock released on resource", b4);

        SemaphoreStep.waitForStart("wait-inside/6", b6);
        j.assertLogContains("locked 6", b6);

        // release 6. (last) lock (on build 6)
        SemaphoreStep.success("wait-inside/6", null);

        // wait for all jobs
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        j.assertBuildStatusSuccess(j.waitForCompletion(b4));
        j.assertBuildStatusSuccess(j.waitForCompletion(b5));
        j.assertBuildStatusSuccess(j.waitForCompletion(b6));
    }

    @Test
    @Issue("GITHUB-560")
    /**
     * start time | job | resource  | priority
     * ------     |---  |---        |---
     * 00:01      | j1  | resource1 | 0
     * 00:02      | j2  | resource1 | <default == 0>
     * 00:03      | j3  | resource1 | -1
     * 00:04      | j4  | resource1 | 10
     * 00:05      | j5  | resource1 | -2
     * 00:06      | j6  | resource1 | 100
     *
     * expected lock order: j1 -> j6 -> j4 -> j2 -> j3 -> j5
     */
    public void lockWithPrio() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");

        // just dummy build to acquire lock
        p.setDefinition(new CpsFlowDefinition(
                "lock(resource: 'resource1', priority: 0) {\n"
                        + "     echo 'locked 1'\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        // build with default priority
        p.setDefinition(new CpsFlowDefinition(
                "lock(resource: 'resource1') {\n"
                        + "     echo 'locked 2'\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b2);

        // build with negative prio
        p.setDefinition(new CpsFlowDefinition(
                "lock(resource: 'resource1', priority: -1) {\n"
                        + "     echo 'locked 3'\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b3);

        // build with positive (low) prio
        p.setDefinition(new CpsFlowDefinition(
                "lock(resource: 'resource1', priority: 10) {\n"
                        + "     echo 'locked 4'\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b4 = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b4);

        // build with negative (lowest) prio
        p.setDefinition(new CpsFlowDefinition(
                "lock(resource: 'resource1', priority: -2) {\n"
                        + "     echo 'locked 5'\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b5 = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b5);

        // build with positive (highest) prio
        p.setDefinition(new CpsFlowDefinition(
                "lock(resource: 'resource1', priority: 100) {\n"
                        + "     echo 'locked 6'\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b6 = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b6);

        // check logs
        j.assertLogContains("locked 1", b1);
        j.assertLogNotContains("locked 2", b2);
        j.assertLogNotContains("locked 3", b3);
        j.assertLogNotContains("locked 4", b4);
        j.assertLogNotContains("locked 5", b5);
        j.assertLogNotContains("locked 6", b6);

        // release first lock (on build 1)
        SemaphoreStep.success("wait-inside/1", null);
        j.waitForMessage("Lock released on resource", b1);

        SemaphoreStep.waitForStart("wait-inside/2", b6);
        j.assertLogNotContains("locked 2", b2);
        j.assertLogNotContains("locked 3", b3);
        j.assertLogNotContains("locked 4", b4);
        j.assertLogNotContains("locked 5", b5);
        j.assertLogContains("locked 6", b6);

        // release 2. lock (on build 6)
        SemaphoreStep.success("wait-inside/2", null);
        j.waitForMessage("Lock released on resource", b6);

        SemaphoreStep.waitForStart("wait-inside/3", b4);
        j.assertLogNotContains("locked 2", b2);
        j.assertLogNotContains("locked 3", b3);
        j.assertLogContains("locked 4", b4);
        j.assertLogNotContains("locked 5", b5);

        // release 3. lock (on build 4)
        SemaphoreStep.success("wait-inside/3", null);
        j.waitForMessage("Lock released on resource", b4);

        SemaphoreStep.waitForStart("wait-inside/4", b2);
        j.assertLogContains("locked 2", b2);
        j.assertLogNotContains("locked 3", b3);
        j.assertLogNotContains("locked 5", b5);

        // release 4. lock (on build 2)
        SemaphoreStep.success("wait-inside/4", null);
        j.waitForMessage("Lock released on resource", b2);

        SemaphoreStep.waitForStart("wait-inside/5", b2);
        j.assertLogContains("locked 3", b3);
        j.assertLogNotContains("locked 5", b5);

        // release 5. lock (on build 3)
        SemaphoreStep.success("wait-inside/5", null);
        j.waitForMessage("Lock released on resource", b3);

        SemaphoreStep.waitForStart("wait-inside/6", b5);
        j.assertLogContains("locked 5", b5);

        // release 6. (last) lock (on build 5)
        SemaphoreStep.success("wait-inside/6", null);

        // wait for all jobs
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        j.assertBuildStatusSuccess(j.waitForCompletion(b4));
        j.assertBuildStatusSuccess(j.waitForCompletion(b5));
        j.assertBuildStatusSuccess(j.waitForCompletion(b6));
    }

    @Test
    public void parallelLock() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "parallel a: {\n"
                        + "	semaphore 'before-a'\n"
                        + "	lock('resource1') {\n"
                        + "		semaphore 'inside-a'\n"
                        + "	}\n"
                        + "}, b: {\n"
                        + "	lock('resource1') {\n"
                        + "		semaphore 'wait-b'\n"
                        + "	}\n"
                        + "}\n",
                true));

        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-b/1", b1);
        SemaphoreStep.waitForStart("before-a/1", b1);
        // both messages are in the log because branch b acquired the lock and branch a is waiting to
        // lock
        j.waitForMessage("Trying to acquire lock on [Resource: resource1]", b1);
        SemaphoreStep.success("before-a/1", null);
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b1);
        isPaused(b1, 2, 1);

        SemaphoreStep.success("wait-b/1", null);

        j.waitForMessage("Trying to acquire lock on [Resource: resource1]", b1);
        SemaphoreStep.waitForStart("inside-a/1", b1);
        isPaused(b1, 2, 0);
        SemaphoreStep.success("inside-a/1", null);

        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        assertNull(LockableResourcesManager.get().fromName("resource1"));
    }

    @Test
    public void unlockButtonWithWaitingRuns() throws Exception {
        LockableResourcesManager.get().createResource("resource1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock('resource1') { semaphore('wait-inside') }", true));

        WorkflowRun prevBuild = null;
        TestHelpers testHelpers = new TestHelpers();
        for (int i = 0; i < 3; i++) {
            WorkflowRun rNext = p.scheduleBuild2(0).waitForStart();
            LOGGER.info("start build " + rNext);
            if (prevBuild != null) {
                LOGGER.info("" + rNext + " waits for locked by " + prevBuild);
                j.waitForMessage("[resource1] is locked by build " + prevBuild.getFullDisplayName(), rNext);
                LOGGER.info("is paused " + rNext);
                isPaused(rNext, 1, 1);
                LOGGER.info("unlock resource1");
                testHelpers.clickButton("unlock", "resource1");
            }

            LOGGER.info("wait for 1 " + rNext);
            j.waitForMessage("Trying to acquire lock on [Resource: resource1]", rNext);

            LOGGER.info("wait sem 1 " + rNext);
            SemaphoreStep.waitForStart("wait-inside/" + (i + 1), rNext);
            LOGGER.info("is paused 1 " + rNext);
            isPaused(rNext, 1, 0);

            if (prevBuild != null) {
                LOGGER.info("wait sem 2" + rNext);
                SemaphoreStep.success("wait-inside/" + i, null);
                j.assertBuildStatusSuccess(j.waitForCompletion(prevBuild));
            }
            prevBuild = rNext;
        }
        LOGGER.info("wait sem 3");
        SemaphoreStep.success("wait-inside/3", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(prevBuild));
    }

    @Issue("JENKINS-40879")
    @Test
    public void parallelLockRelease() throws Exception {
        LockableResourcesManager.get().createResource("resource1");
        LockableResourcesManager.get().createResource("resource2");
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "j");
        job.setDefinition(new CpsFlowDefinition(
                "lock(resource: 'resource1') {\n"
                        + "    semaphore 'wait-inside-1'\n"
                        + "}\n"
                        + "lock(resource: 'resource2') { \n"
                        + "    echo 'Entering semaphore now'\n"
                        + "    semaphore 'wait-inside-2'\n"
                        + "}\n",
                true));

        List<WorkflowRun> nextRuns = new ArrayList<>();

        WorkflowRun toUnlock = null;
        for (int i = 0; i < 5; i++) {
            WorkflowRun rNext = job.scheduleBuild2(0).waitForStart();
            if (toUnlock != null) {
                j.waitForMessage("[resource1] is locked by build " + toUnlock.getFullDisplayName(), rNext);
                isPaused(rNext, 1, 1);
                SemaphoreStep.success("wait-inside-1/" + i, null);
            }
            SemaphoreStep.waitForStart("wait-inside-1/" + (i + 1), rNext);
            isPaused(rNext, 1, 0);
            nextRuns.add(rNext);
            toUnlock = rNext;
        }
        SemaphoreStep.success("wait-inside-1/" + nextRuns.size(), null);
        waitAndClear(1, nextRuns);
    }

    private void waitAndClear(int semaphoreIndex, List<WorkflowRun> nextRuns) throws Exception {
        WorkflowRun toClear = nextRuns.get(0);

        LOGGER.info("Waiting for semaphore to start for " + toClear.getNumber());
        SemaphoreStep.waitForStart("wait-inside-2/" + semaphoreIndex, toClear);

        List<WorkflowRun> remainingRuns = new ArrayList<>();

        if (nextRuns.size() > 1) {
            remainingRuns.addAll(nextRuns.subList(1, nextRuns.size()));

            for (WorkflowRun r : remainingRuns) {
                LOGGER.info("Verifying no semaphore yet for " + r.getNumber());
                j.assertLogNotContains("Entering semaphore now", r);
            }
        }

        SemaphoreStep.success("wait-inside-2/" + semaphoreIndex, null);
        LOGGER.info("Waiting for " + toClear.getNumber() + " to complete");
        j.assertBuildStatusSuccess(j.waitForCompletion(toClear));

        if (!remainingRuns.isEmpty()) {
            waitAndClear(semaphoreIndex + 1, remainingRuns);
        }
    }

    @Test
    @WithPlugin("jobConfigHistory.hpi")
    public void lockWithLabelConcurrent() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        final WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "import java.util.Random; \n"
                        + "Random random = new Random(0);\n"
                        + "lock(label: 'label1') {\n"
                        + "  echo 'Resource locked'\n"
                        + "  sleep random.nextInt(10)*100\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        final CyclicBarrier barrier = new CyclicBarrier(51);
        for (int i = 0; i < 50; i++) {
            Thread thread = new Thread(() -> {
                try {
                    barrier.await();
                    p.scheduleBuild2(0).waitForStart();
                } catch (Exception e) {
                    LOGGER.info("Failed to start pipeline job");
                }
            });
            thread.start();
        }
        barrier.await();
        j.waitUntilNoActivity();
    }

    @Test
    public void lockMultipleResources() throws Exception {
        LockableResourcesManager.get().createResource("resource1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(resource: 'resource1', extra: [[resource: 'resource2']]) {\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
        p2.setDefinition(new CpsFlowDefinition(
                "lock('resource1') {\n" + "	semaphore 'wait-inside-p2'\n" + "}\n" + "echo 'Finish'", true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b2);
        isPaused(b2, 1, 1);

        WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
        p3.setDefinition(new CpsFlowDefinition(
                "lock('resource2') {\n" + "	semaphore 'wait-inside-p3'\n" + "}\n" + "echo 'Finish'", true));
        WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource2] is locked by build " + b1.getFullDisplayName(), b3);
        isPaused(b3, 1, 1);

        // Unlock resources
        SemaphoreStep.success("wait-inside/1", null);
        j.waitForMessage("Lock released on resource [{Resource: resource1},{Resource: resource2}]", b1);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        isPaused(b1, 1, 0);

        // Both get their lock
        j.waitForMessage("Trying to acquire lock on [Resource: resource1]", b2);
        j.waitForMessage("Lock acquired on [Resource: resource2]", b3);

        SemaphoreStep.success("wait-inside-p2/1", null);
        SemaphoreStep.success("wait-inside-p3/1", null);
        j.waitForMessage("Finish", b2);
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        j.waitForMessage("Finish", b3);
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        isPaused(b2, 1, 0);
        isPaused(b3, 1, 0);
    }

    @Test
    public void lockWithLabelAndResource() throws Exception {
        LockableResourcesManager.get().createResource("resource1");
        LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
        LockableResourcesManager.get().createResourceWithLabel("resource3", "label1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', extra: [[resource: 'resource1']]) {\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
        p2.setDefinition(new CpsFlowDefinition(
                "lock('resource1') {\n" + "	semaphore 'wait-inside-p2'\n" + "}\n" + "echo 'Finish'", true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b2);
        isPaused(b2, 1, 1);

        WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
        p3.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1') {\n" + "	semaphore 'wait-inside-p3'\n" + "}\n" + "echo 'Finish'", true));
        WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Label: label1] is not free, waiting for execution ...", b3);
        isPaused(b3, 1, 1);

        // Unlock resources
        SemaphoreStep.success("wait-inside/1", null);
        j.waitForMessage("Lock released on resource [{Label: label1},{Resource: resource1}]", b1);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        isPaused(b2, 1, 0);

        // Both get their lock
        j.waitForMessage("Trying to acquire lock on [Resource: resource1]", b2);
        j.waitForMessage("Lock acquired on [Label: label1]", b3);

        SemaphoreStep.success("wait-inside-p2/1", null);
        SemaphoreStep.success("wait-inside-p3/1", null);
        j.waitForMessage("Finish", b2);
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        j.waitForMessage("Finish", b3);
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        isPaused(b2, 1, 0);
        isPaused(b3, 1, 0);
    }

    @Test
    public void lockWithLabelAndLabeledResource() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
        LockableResourcesManager.get().createResourceWithLabel("resource3", "label1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', extra: [[resource: 'resource1']]) {\n"
                        + "	semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

        j.waitForMessage("Extra filter tries to allocate pre-reserved resources.", b1);

        SemaphoreStep.waitForStart("wait-inside/1", b1);

        WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
        p2.setDefinition(new CpsFlowDefinition(
                "lock('resource1') {\n" + "	semaphore 'wait-inside-p2'\n" + "}\n" + "echo 'Finish'", true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b2);
        isPaused(b2, 1, 1);

        WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
        p3.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1') {\n" + "	semaphore 'wait-inside-p3'\n" + "}\n" + "echo 'Finish'", true));
        WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Label: label1] is not free, waiting for execution ...", b3);
        isPaused(b3, 1, 1);

        // Unlock resources
        SemaphoreStep.success("wait-inside/1", null);
        j.waitForMessage("Lock released on resource [{Label: label1},{Resource: resource1}]", b1);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        isPaused(b1, 1, 0);

        // #2 gets the lock before #3 (in the order as they requested the lock)
        j.waitForMessage("Trying to acquire lock on [Resource: resource1]", b2);
        SemaphoreStep.success("wait-inside-p2/1", null);
        j.waitForMessage("Finish", b2);
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        isPaused(b2, 1, 0);
        j.waitForMessage("Lock acquired on [Label: label1]", b3);
        SemaphoreStep.success("wait-inside-p3/1", null);
        j.waitForMessage("Finish", b3);
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        isPaused(b3, 1, 0);

        assertNotNull(LockableResourcesManager.get().fromName("resource1"));
        assertNotNull(LockableResourcesManager.get().fromName("resource2"));
        assertNotNull(LockableResourcesManager.get().fromName("resource3"));
    }

    @Test
    public void lockWithLabelAndLabeledResourceQuantity() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
        LockableResourcesManager.get().createResourceWithLabel("resource3", "label1");
        LockableResourcesManager.get().createResourceWithLabel("resource4", "label1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(variable: 'var', extra: [[resource: 'resource4'], [resource: 'resource2'], "
                        + "[label: 'label1', quantity: 2]]) {\n"
                        + "  def lockedResources = env.var.split(',').sort()\n"
                        + "  echo \"Resources locked: ${lockedResources}\"\n"
                        + "  semaphore 'wait-inside'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        // #1 should lock as few resources as possible
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        j.waitForMessage("Extra filter tries to allocate pre-reserved resources.", b1);
        j.waitForMessage("Resources locked: [resource2, resource4]", b1);

        WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
        p2.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', variable: 'var', quantity: 3) {\n"
                        + "	def lockedResources = env.var.split(',').sort()\n"
                        + "	echo \"Resources locked: ${lockedResources}\"\n"
                        + "	semaphore 'wait-inside-quantity3'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Label: label1, Quantity: 3] is not free, waiting for execution ...", b2);
        j.waitForMessage("Found 2 available resource(s). Waiting for correct amount: 3.", b2);
        isPaused(b2, 1, 1);

        WorkflowJob p3 = j.jenkins.createProject(WorkflowJob.class, "p3");
        p3.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', variable: 'var', quantity: 2) {\n"
                        + "	def lockedResources = env.var.split(',').sort()\n"
                        + "	echo \"Resources locked: ${lockedResources}\"\n"
                        + "	semaphore 'wait-inside-quantity2'\n"
                        + "}\n"
                        + "echo 'Finish'",
                true));
        WorkflowRun b3 = p3.scheduleBuild2(0).waitForStart();
        // While 2 continues waiting, 3 can continue directly
        SemaphoreStep.waitForStart("wait-inside-quantity2/1", b3);
        // Let 3 finish
        SemaphoreStep.success("wait-inside-quantity2/1", null);
        j.waitForMessage("Finish", b3);
        j.assertBuildStatusSuccess(j.waitForCompletion(b3));
        j.assertLogContains("Resources locked: [resource1, resource3]", b3);
        isPaused(b3, 1, 0);

        // Unlock resources
        SemaphoreStep.success("wait-inside/1", null);
        j.waitForMessage(
                "Lock released on resource [{Resource: resource4},{Resource: resource2},{Label: label1, Quantity: 2}]",
                b1);
        j.assertLogContains("Resources locked: [resource2, resource4]", b1);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        isPaused(b1, 1, 0);

        // #2 gets the lock
        j.waitForMessage("Lock acquired on [Label: label1, Quantity: 3]", b2);
        SemaphoreStep.success("wait-inside-quantity3/1", null);
        j.waitForMessage("Finish", b2);
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        // Could be any 3 resources, so just check the beginning of the message
        j.assertLogContains("Resources locked: [resource", b2);
        isPaused(b2, 1, 0);

        assertNotNull(LockableResourcesManager.get().fromName("resource1"));
        assertNotNull(LockableResourcesManager.get().fromName("resource2"));
        assertNotNull(LockableResourcesManager.get().fromName("resource3"));
        assertNotNull(LockableResourcesManager.get().fromName("resource4"));
    }

    @Test
    // @Issue("JENKINS-XXXXX")
    public void multipleLocksFillVariables() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', variable: 'someVar', quantity: 2) {\n"
                        + "  echo \"VAR IS ${env.someVar.split(',').sort()}\"\n"
                        + "  echo \"VAR0or1 IS $env.someVar0\"\n"
                        + "  echo \"VAR0or1 IS $env.someVar1\"\n"
                        + "  echo \"VAR2 IS $env.someVar2\"\n"
                        + "}",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        // Variable should have been filled
        j.assertLogContains("VAR IS [resource1, resource2]", b1);
        j.assertLogContains("VAR0or1 IS resource1", b1);
        j.assertLogContains("VAR0or1 IS resource2", b1);
        j.assertLogContains("VAR2 IS null", b1);
    }

    @Test
    // @Issue("JENKINS-XXXXX")
    public void locksInVariablesAreInTheRequestedOrder() throws Exception {
        List<String> extras = new ArrayList<>();
        List<String> eachVar = new ArrayList<>();
        for (int i = 0; i < 100; ++i) {
            LockableResourcesManager.get().createResource("extra" + i);
            extras.add("[resource: 'extra" + i + "', quantity:1]");
            eachVar.add("  echo \"VAR" + (i + 1) + " IS ${env.var" + (i + 1) + "}\"\n");
        }
        LockableResourcesManager.get().createResource("main");

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(variable: 'var', resource: 'main', quantity: 1, extra: ["
                        + String.join(",", extras)
                        + "]) {\n"
                        + "  echo \"VAR IS ${env.var}\"\n"
                        + "  echo \"VAR0 IS ${env.var0}\"\n"
                        + String.join("\n", eachVar)
                        + "}",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        // Variable should have been filled
        j.assertLogContains(
                "VAR IS main,extra0,extra1,extra2,extra3,extra4,extra5,extra6,extra7,extra8,extra9,extra10,extra11,"
                        + "extra12,extra13,extra14,extra15,extra16,extra17,extra18,extra19,extra20,extra21,extra22,extra23,extra24,extra25,extra26,"
                        + "extra27,extra28,extra29,extra30,extra31,extra32,extra33,extra34,extra35,extra36,extra37,extra38,extra39,extra40,extra41,"
                        + "extra42,extra43,extra44,extra45,extra46,extra47,extra48,extra49,extra50,extra51,extra52,extra53,extra54,extra55,extra56,"
                        + "extra57,extra58,extra59,extra60,extra61,extra62,extra63,extra64,extra65,extra66,extra67,extra68,extra69,extra70,extra71,"
                        + "extra72,extra73,extra74,extra75,extra76,extra77,extra78,extra79,extra80,extra81,extra82,extra83,extra84,extra85,extra86,"
                        + "extra87,extra88,extra89,extra90,extra91,extra92,extra93,extra94,extra95,extra96,extra97,extra98,extra99",
                b1);

        j.assertLogContains("VAR0 IS main", b1);
        for (int i = 0; i < 100; ++i) {
            j.assertLogContains("VAR" + (i + 1) + " IS extra" + i, b1);
        }
    }

    @Test
    @Issue("JENKINS-50176")
    public void lockWithLabelFillsVariable() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', variable: 'someVar') {\n"
                        + "  semaphore 'wait-inside'\n"
                        + "  echo \"VAR IS $env.someVar\"\n"
                        + "}",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p2");
        p2.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', variable: 'someVar2') {\n" + "  echo \"VAR2 IS $env.someVar2\"\n" + "}", true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        j.waitForMessage(", waiting for execution ...", b2);
        isPaused(b2, 1, 1);

        // Unlock resources
        SemaphoreStep.success("wait-inside/1", null);
        j.waitForMessage("Lock released on resource", b1);
        isPaused(b1, 1, 0);

        // Now job 2 should get and release the lock...
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        isPaused(b2, 1, 0);

        // Variable should have been filled in both cases
        j.assertLogContains("VAR IS resource1", b1);
        j.assertLogContains("VAR2 IS resource1", b2);
    }

    @Test
    @Issue("JENKINS-50176")
    public void parallelLockWithLabelFillsVariable() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "parallel p1: {\n"
                        + "  lock(label: 'label1', variable: 'someVar') {\n"
                        + "    semaphore 'wait-inside'\n"
                        + "    echo \"VAR IS $env.someVar\"\n"
                        + "  }\n"
                        + "},\n"
                        + "p2: {\n"
                        + "  semaphore 'wait-outside'\n"
                        + "  lock(label: 'label1', variable: 'someVar2') {\n"
                        + "    echo \"VAR2 IS $env.someVar2\"\n"
                        + "  }\n"
                        + "}",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-outside/1", b1);
        SemaphoreStep.waitForStart("wait-inside/1", b1);
        SemaphoreStep.success("wait-outside/1", null);

        j.waitForMessage(", waiting for execution ...", b1);
        isPaused(b1, 2, 1);

        // Unlock resources
        SemaphoreStep.success("wait-inside/1", null);
        j.waitForMessage("Lock released on resource", b1);

        // wait until both resources are free.
        // otherwise the next isPaused() check might fail
        j.waitForMessage("VAR2 IS", b1);
        // this sometimes fails, but it does not looks like a failure in the plugin self
        // and it is not necessary to check it here, because this test case will check
        // env variable resolution and not if the paused actions works
        // therefore, when it fails again, you can comment it out.
        // isPaused(b1, 2, 0);

        // Now the second parallel branch should get and release the lock...
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        isPaused(b1, 2, 0);

        // Variable should have been filled in both cases
        j.assertLogContains("VAR IS resource1", b1);
        j.assertLogContains("VAR2 IS resource1", b1);
    }

    @Test
    @Issue("JENKINS-54541")
    public void unreserveSetsVariable() throws Exception {
        LockableResourcesManager lm = LockableResourcesManager.get();
        lm.createResourceWithLabel("resource1", "label1");
        lm.reserve(Collections.singletonList(lm.fromName("resource1")), "test");

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', variable: 'someVar') {\n" + "  echo \"VAR IS $env.someVar\"\n" + "}", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

        j.waitForMessage(", waiting for execution ...", b1);
        lm.unreserve(Collections.singletonList(lm.fromName("resource1")));
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        // Variable should have been filled
        j.assertLogContains("VAR IS resource1", b1);
    }

    @Test
    // @Issue("JENKINS-XXXXX")
    public void reserveInsideLockHonoured() throws Exception {

        // to see detailed lock causes
        System.setProperty(Constants.SYSTEM_PROPERTY_PRINT_BLOCKED_RESOURCE, "-1");
        System.setProperty(Constants.SYSTEM_PROPERTY_PRINT_QUEUE_INFO, "-1");

        // Use-case is a job keeping the resource reserved so it can use
        // it in other stages and free it later, not all in one closure
        // Variant: using the LockableResourcesManager to manipulate
        // the LockableResource object(s) (with its synchronized code)
        LockableResourcesManager lm = LockableResourcesManager.get();
        lm.createResourceWithLabel("resource1", "label1");

        // Can't store in CPS script variable because not serializable:
        String lmget = "org.jenkins.plugins.lockableresources.LockableResourcesManager.get()";
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "reserveInsideLockHonoured");
        p.setDefinition(new CpsFlowDefinition(
                "timeout(2) {\n"
                        + "parallel p1: {\n"
                        + "  org.jenkins.plugins.lockableresources.LockableResource lr = null\n"
                        + "  lock(label: 'label1', variable: 'LOCK_NAME') {\n"
                        + "    echo \"VAR IS $env.LOCK_NAME\"\n"
                        + "    lr = "
                        + lmget
                        + ".fromName(env.LOCK_NAME)\n"
                        + "    echo \"Locked resource cause 1-1: ${lr.getLockCause()}\"\n"
                        + "    echo \"Locked resource reservedBy 1-1: ${lr.getReservedBy()}\"\n"
                        + "    def res = "
                        + lmget
                        + ".reserve([lr], 'test2a')\n"
                        // + "    semaphore 'wait-inside'\n"
                        + "    echo \"Locked resource cause 1-2a: ${lr.getLockCause()}\"\n"
                        + "    echo \"Locked resource reservedBy 1-2a: ${lr.getReservedBy()}\"\n"
                        + "    if (!res) {\n"
                        + "        echo \"LockableResourcesManager did not reserve an already locked resource; hack it!\"\n"
                        + "        lr.setReservedBy('test2b')\n"
                        + "        echo \"Locked resource cause 1-2b: ${lr.getLockCause()}\"\n"
                        + "        echo \"Locked resource reservedBy 1-2b: ${lr.getReservedBy()}\"\n"
                        + "    }\n"
                        + "    echo \"Unlocking parallel closure 1\"\n"
                        + "  }\n"
                        + "  echo \"Locked resource cause 1-3 (after unlock): ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-3: ${lr.getReservedBy()}\"\n"
                        + "  echo \"Ended locked parallel closure 1 with resource reserved, sleeping...\"\n"
                        + "  sleep (5)\n"
                        + "  echo \"Locked resource cause 1-4: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-4: ${lr.getReservedBy()}\"\n"
                        + "  echo \"Resetting Locked resource via LRM and sleeping ...\"\n"
                        + "  "
                        + lmget
                        + ".reset([lr])\n"
                        + "  sleep (5)\n"
                        + "  echo \"Un-reserving Locked resource via LRM and sleeping...\"\n"
                        + "  "
                        + lmget
                        + ".unreserve([lr])\n"
                        + "  sleep (5)\n"
                        // Note: the unlock attempt here might steal this resource
                        // from another parallel stage, so we don't do it:
                        // + "  echo \"Un-locking Locked resource via LRM and sleeping...\"\n"
                        // + "  " + lmget + ".unlock([lr], null)\n"
                        // + "  sleep (5)\n"
                        + "  echo \"Locked resource cause 1-5: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-5: ${lr.getReservedBy()}\"\n"
                        + "  sleep (5)\n"
                        + "  if (lr.getLockCause() == null) {\n"
                        + "    echo \"LRM seems stuck; trying to reserve/unreserve this resource by LRM methods\"\n"
                        // + "    lock(label: 'label1') { echo \"Secondary lock trick\" }\n"
                        + "    if ("
                        + lmget
                        + ".reserve([lr], 'unstucker')) {\n"
                        + "        echo \"Secondary lock trick\"\n"
                        + "        "
                        + lmget
                        + ".unreserve([lr])\n"
                        + "    } else { echo \"Could not reserve by LRM methods as 'unstucker'\" }\n"
                        + "  }\n"
                        + "  sleep (5)\n"
                        + "  echo \"Locked resource cause 1-6: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-6: ${lr.getReservedBy()}\"\n"
                        + "},\n"
                        + "p2: {\n"
                        // + "  semaphore 'wait-outside'\n"
                        + "  org.jenkins.plugins.lockableresources.LockableResource lr = null\n"
                        + "  sleep 1\n"
                        + "  echo \"Locked resource cause 2-1: not locked yet\"\n"
                        + "  lock(label: 'label1', variable: 'someVar2') {\n"
                        + "    echo \"VAR2 IS $env.someVar2\"\n"
                        + "    lr = "
                        + lmget
                        + ".fromName(env.someVar2)\n"
                        + "    sleep (1)\n"
                        + "    echo \"Locked resource cause 2-2: ${lr.getLockCause()}\"\n"
                        + "    echo \"Locked resource reservedBy 2-2: ${lr.getReservedBy()}\"\n"
                        + "    echo \"Setting (directly) and dropping (via LRM) a reservation on locked resource\"\n"
                        + "    lr.reserve('test2-1')\n"
                        + "    sleep (3)\n"
                        + "    "
                        + lmget
                        + ".unreserve([lr])\n"
                        + "    echo \"Just sleeping...\"\n"
                        + "    sleep (20)\n"
                        + "    echo \"Setting (directly) a reservation on locked resource\"\n"
                        + "    lr.reserve('test2-2')\n"
                        + "    echo \"Unlocking parallel closure 2\"\n"
                        + "  }\n"
                        + "  echo \"Locked resource cause 2-3: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 2-3: ${lr.getReservedBy()}\"\n"
                        + "  sleep (5)\n"
                        + "  echo \"Recycling (via LRM) the reserved not-locked resource\"\n"
                        + "  "
                        + lmget
                        + ".recycle([lr])\n"
                        + "  sleep (5)\n"
                        + "  echo \"Locked resource cause 2-4: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 2-4: ${lr.getReservedBy()}\"\n"
                        + "},\n"
                        // Test that reserve/unreserve in p2 did not "allow" p3 to kidnap the lock:
                        + "p3: {\n"
                        + "  org.jenkins.plugins.lockableresources.LockableResource lr = null\n"
                        + "  echo \"Locked resource cause 3-1: not locked yet\"\n"
                        + "  sleep 2\n"
                        + "  lock(label: 'label1', variable: 'someVar3') {\n"
                        + "    echo \"VAR3 IS $env.someVar3\"\n"
                        + "    lr = "
                        + lmget
                        + ".fromName(env.someVar3)\n"
                        + "    echo \"Locked resource cause 3-2: ${lr.getLockCause()}\"\n"
                        + "    echo \"Locked resource reservedBy 3-2: ${lr.getReservedBy()}\"\n"
                        + "    echo \"Just sleeping...\"\n"
                        + "    sleep (10)\n"
                        + "    echo \"Unlocking parallel closure 3\"\n"
                        + "  }\n"
                        + "  echo \"Locked resource cause 3-3: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 3-3: ${lr.getReservedBy()}\"\n"
                        + "}\n"
                        + "\necho \"Survived the test\"\n"
                        + "}", // timeout wrapper
                false));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

        j.waitForMessage("Locked resource cause 1-1", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);

        j.waitForMessage("Locked resource cause 1-2", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);

        j.waitForMessage("Locked resource cause 1-3", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);

        // Bug #1 happens here (without further patch):
        // although resource is seen as reserved, it is
        // grabbed anyway by the other parallel thread
        // which is already waiting. Notably, log is like:
        //  62.908 [setReservedByInsideLockHonoured #1] Lock acquired on [Label: label1]
        //  62.909 [setReservedByInsideLockHonoured #1] Lock released on resource [Label: label1]
        // and the consistent ordering of acquired first,
        // released later is unsettling.
        j.waitForMessage("Locked resource cause 1-4", b1);
        // Note: stage in test has a sleep(1) to reduce chances that
        // this line is noticed in log although it is there AFTER 1-4:
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);
        LOGGER.info("GOOD: Did not encounter Bug #1 " + "(parallel p2 gets the lock on a still-reserved resource)!");

        j.waitForMessage("Locked resource cause 1-5", b1);
        // This line might not strictly be required,
        // but we are processing a parallel pipeline
        // and many seconds were spent sleeping, so:
        j.assertLogContains("Locked resource cause 2-1", b1);
        // Here the other parallel stage may have already started
        // (we try to recycle the resource between 1-4 and 1-5):
        // j.assertLogNotContains("Locked resource cause 2-2", b1);
        // j.assertLogNotContains("Locked resource cause 2-3", b1);

        // Bug #2 happens here: even after the resource is known
        // to be un-reserved, resources already looping waiting
        // for it (after the fix for Bug #1) are not "notified".
        // Adding and removing the resource helps unblock this.
        boolean sawBug2a = false;
        try {
            j.waitForMessage("Locked resource cause 1-6", b1);
            j.assertLogContains("Locked resource cause 2-2", b1);
        } catch (java.lang.AssertionError t1) {
            sawBug2a = true;
            LOGGER.info("Bug #2a (Parallel 2 did not start after Parallel 1 finished "
                    + "and resource later released) currently tolerated");
            // LOGGER.info(t1.toString());
            // throw t1;
        }
        if (!sawBug2a) {
            LOGGER.info("GOOD: Did not encounter Bug #2a "
                    + "(Parallel 2 did not start after Parallel 1 finished "
                    + "and resource later released)!");
        }

        // If the bug is resolved, then by the time we get to 1-5
        // the resource should be taken by the other parallel stage
        // and so not locked by not-"null"; reservation should be away though
        boolean sawBug2b = false;
        j.assertLogContains("Locked resource reservedBy 1-5: null", b1);
        for (String line : new String[] {
            "Locked resource cause 1-5: null", "LRM seems stuck; trying to reserve/unreserve", "Secondary lock trick"
        }) {
            try {
                j.assertLogNotContains(line, b1);
            } catch (java.lang.AssertionError t2) {
                sawBug2b = true;
                LOGGER.info("Bug #2b (LRM required un-stucking) currently tolerated: " + line);
                // LOGGER.info(t2.toString());
                // throw t2;
            }
        }
        if (!sawBug2b) {
            LOGGER.info("GOOD: Did not encounter Bug #2b " + "(LRM required un-stucking)!");
        }

        j.waitForMessage("Locked resource cause 2-2", b1);
        j.assertLogContains("Locked resource cause 1-5", b1);
        LOGGER.info("GOOD: lock#2 was taken after we un-reserved lock#1");

        j.waitForMessage("Unlocking parallel closure 2", b1);
        j.assertLogNotContains("Locked resource cause 3-2", b1);
        LOGGER.info("GOOD: lock#3 was NOT taken just after we un-locked closure 2 (keeping lock#2 reserved)");

        // After 2-3 we lrm.recycle() the lock so it should
        // go to the next bidder
        j.waitForMessage("Locked resource cause 2-4", b1);
        j.assertLogContains("Locked resource cause 3-2", b1);
        LOGGER.info("GOOD: lock#3 was taken just after we recycled lock#2");

        j.assertLogContains(", waiting for execution ...", b1);

        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        j.assertLogContains("Survived the test", b1);
    }

    @Test
    // @Issue("JENKINS-XXXXX")
    public void setReservedByInsideLockHonoured() throws Exception {
        // Use-case is a job keeping the resource reserved so it can use
        // it in other stages and free it later, not all in one closure
        // Variant: directly using the LockableResource object
        LockableResourcesManager lm = LockableResourcesManager.get();
        lm.createResourceWithLabel("resource1", "label1");

        // Can't store in CPS script variable because not serializable:
        String lmget = "org.jenkins.plugins.lockableresources.LockableResourcesManager.get()";
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "setReservedByInsideLockHonoured");
        p.setDefinition(new CpsFlowDefinition(
                "timeout(2) {\n"
                        + "parallel p1: {\n"
                        + "  org.jenkins.plugins.lockableresources.LockableResource lr = null\n"
                        + "  lock(label: 'label1', variable: 'LOCK_NAME') {\n"
                        + "    echo \"VAR IS $env.LOCK_NAME\"\n"
                        + "    lr = "
                        + lmget
                        + ".fromName(env.LOCK_NAME)\n"
                        + "    echo \"Locked resource cause 1-1: ${lr.getLockCause()}\"\n"
                        + "    echo \"Locked resource reservedBy 1-1: ${lr.getReservedBy()}\"\n"
                        + "    lr.setReservedBy('test')\n"
                        // + "    semaphore 'wait-inside'\n"
                        + "    echo \"Locked resource cause 1-2: ${lr.getLockCause()}\"\n"
                        + "    echo \"Locked resource reservedBy 1-2: ${lr.getReservedBy()}\"\n"
                        + "    echo \"Unlocking parallel closure 1\"\n"
                        + "  }\n"
                        + "  echo \"Locked resource cause 1-3 (after unlock): ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-3: ${lr.getReservedBy()}\"\n"
                        + "  echo \"Ended locked parallel closure 1 with resource reserved, sleeping...\"\n"
                        + "  sleep (5)\n"
                        + "  echo \"Locked resource cause 1-4: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-4: ${lr.getReservedBy()}\"\n"
                        // Note: lr.reset() only nullifies the fields in LR instance
                        // but does not help a queue get moving
                        // + "  echo \"Un-reserving Locked resource directly as `lr.reset()` and
                        // sleeping...\"\n"
                        // + "  lr.reset()\n"
                        + "  echo \"Un-reserving Locked resource directly as `lr.recycle()` and sleeping...\"\n"
                        + "  lr.recycle()\n"
                        + "  sleep (5)\n"
                        + "  echo \"Locked resource cause 1-5: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-5: ${lr.getReservedBy()}\"\n"
                        + "  sleep (5)\n"
                        + "  if (lr.getLockCause() == null) {\n"
                        + "    echo \"LRM seems stuck; trying to reserve/unreserve this resource by lock step\"\n"
                        + "    lock(label: 'label1', skipIfLocked: true) { echo \"Secondary lock trick\" }\n"
                        + "  }\n"
                        + "  sleep (5)\n"
                        + "  echo \"Locked resource cause 1-6: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 1-6: ${lr.getReservedBy()}\"\n"
                        + "},\n"
                        + "p2: {\n"
                        // + "  semaphore 'wait-outside'\n"
                        + "  org.jenkins.plugins.lockableresources.LockableResource lr = null\n"
                        + "  echo \"Locked resource cause 2-1: not locked yet\"\n"
                        + "  lock(label: 'label1', variable: 'someVar2') {\n"
                        + "    echo \"VAR2 IS $env.someVar2\"\n"
                        + "    lr = "
                        + lmget
                        + ".fromName(env.someVar2)\n"
                        + "    sleep (1)\n"
                        + "    echo \"Locked resource cause 2-2: ${lr.getLockCause()}\"\n"
                        + "    echo \"Locked resource reservedBy 2-2: ${lr.getReservedBy()}\"\n"
                        + "    echo \"Just sleeping...\"\n"
                        + "    sleep (20)\n"
                        + "    echo \"Unlocking parallel closure 2\"\n"
                        + "  }\n"
                        + "  echo \"Locked resource cause 2-3: ${lr.getLockCause()}\"\n"
                        + "  echo \"Locked resource reservedBy 2-3: ${lr.getReservedBy()}\"\n"
                        + "},\n"
                        // Add some pressure to try for race conditions:
                        + "p3: { sleep 2; lock(label: 'label1') { sleep 2 } },\n"
                        + "p4: { sleep 2; lock(label: 'label1') { sleep 1 } },\n"
                        + "p5: { sleep 2; lock(label: 'label1') { sleep 3 } },\n"
                        + "p6: { sleep 2; lock(label: 'label1') { sleep 2 } },\n"
                        + "p7: { sleep 2; lock(label: 'label1') { sleep 1 } },\n"
                        + "p8: { sleep 2; lock(label: 'label1') { sleep 2 } },\n"
                        + "p9: { sleep 2; lock(label: 'label1') { sleep 1 } }\n"
                        + "\necho \"Survived the test\"\n"
                        + "}", // timeout wrapper
                false));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

        j.waitForMessage("Locked resource cause 1-1", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);

        j.waitForMessage("Locked resource cause 1-2", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);

        j.waitForMessage("Locked resource cause 1-3", b1);
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);

        // Bug #1 happens here (without further patch):
        // although resource is seen as reserved, it is
        // grabbed anyway by the other parallel thread
        // which is already waiting. Notably, log is like:
        //  62.908 [setReservedByInsideLockHonoured #1] Lock acquired on [Label: label1]
        //  62.909 [setReservedByInsideLockHonoured #1] Lock released on resource [Label: label1]
        // and the consistent ordering of acquired first,
        // released later is unsettling.
        j.waitForMessage("Locked resource cause 1-4", b1);
        // Note: stage in test has a sleep(1) to reduce chances that
        // this line is noticed in log although it is there AFTER 1-4:
        j.assertLogNotContains("Locked resource cause 2-2", b1);
        j.assertLogNotContains("Locked resource cause 2-3", b1);
        LOGGER.info("GOOD: Did not encounter Bug #1 " + "(parallel p2 gets the lock on a still-reserved resource)!");

        j.waitForMessage("Locked resource cause 1-5", b1);
        // This line might not strictly be required,
        // but we are processing a parallel pipeline
        // and many seconds were spent sleeping, so:
        j.assertLogContains("Locked resource cause 2-1", b1);
        // Here the other parallel stage may have already started
        // (we try to recycle the resource between 1-4 and 1-5):
        // j.assertLogNotContains("Locked resource cause 2-2", b1);
        // j.assertLogNotContains("Locked resource cause 2-3", b1);

        // Bug #2 happens here: even after the resource is known
        // to be un-reserved, resources already looping waiting
        // for it (after the fix for Bug #1) are not "notified".
        // Adding and removing the resource helps unblock this.
        boolean sawBug2a = false;
        try {
            j.waitForMessage("Locked resource cause 1-6", b1);
            j.assertLogContains("Locked resource cause 2-2", b1);
        } catch (java.lang.AssertionError t1) {
            sawBug2a = true;
            LOGGER.info("Bug #2a (Parallel 2 did not start after Parallel 1 finished "
                    + "and resource later released) currently tolerated");
            // LOGGER.info(t1.toString());
            // throw t1;
        }
        if (!sawBug2a) {
            LOGGER.info("GOOD: Did not encounter Bug #2a "
                    + "(Parallel 2 did not start after Parallel 1 finished "
                    + "and resource later released)!");
        }

        // If the bug is resolved, then by the time we get to 1-5
        // the resource should be taken by the other parallel stage
        // and so not locked by not-"null"; reservation should be away though
        boolean sawBug2b = false;
        j.assertLogContains("Locked resource reservedBy 1-5: null", b1);
        for (String line : new String[] {
            "Locked resource cause 1-5: null", "LRM seems stuck; trying to reserve/unreserve", "Secondary lock trick"
        }) {
            try {
                j.assertLogNotContains(line, b1);
            } catch (java.lang.AssertionError t2) {
                sawBug2b = true;
                LOGGER.info("Bug #2b (LRM required un-stucking) currently tolerated: " + line);
                // LOGGER.info(t2.toString());
                // throw t2;
            }
        }
        if (!sawBug2b) {
            LOGGER.info("GOOD: Did not encounter Bug #2b " + "(LRM required un-stucking)!");
        }

        /*
            j.assertLogContains("Locked resource cause 1-5: null", b1);
            j.assertLogContains("Locked resource reservedBy 1-5: null", b1);
            try {
                j.assertLogNotContains("LRM seems stuck; trying to reserve/unreserve", b1);
                j.assertLogNotContains("Secondary lock trick", b1);
            } catch (java.lang.AssertionError t2) {
                LOGGER.info("Bug #2b (LRM required un-stucking) currently tolerated");
                //LOGGER.info(t2.toString());
                // throw t2;
            }
        */

        j.waitForMessage("Locked resource cause 2-2", b1);
        j.assertLogContains("Locked resource cause 1-5", b1);

        j.assertLogContains(", waiting for execution ...", b1);

        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        j.assertLogContains("Survived the test", b1);
    }

    @Test
    public void lockWithInvalidLabel() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock(label: 'invalidLabel') {\n" + "}\n", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b1));
        j.assertLogContains("The resource label does not exist: invalidLabel", b1);
        isPaused(b1, 0, 0);
    }

    @Test
    public void inversePrecedenceAndPriorityAreSet() throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', inversePrecedence: true, priority: -1000000000) {}", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b1));
        j.assertLogContains("The \"inverse precedence\" option is not compatible with \"queue priority\" option!", b1);
    }

    @Test
    public void skipIfLocked() throws Exception {
        LockableResourcesManager lm = LockableResourcesManager.get();
        lm.createResourceWithLabel("resource1", "label1");
        lm.reserve(Collections.singletonList(lm.fromName("resource1")), "test");

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(resource: 'resource1', skipIfLocked: true) {\n" + "  echo 'Running body'\n" + "}", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        // check: The resource [resource1] is reserved by test at Sep 1, 2023, 8:29 PM, skipping
        // execution...
        j.assertLogContains("The resource [resource1] is reserved by test", b1);
        j.assertLogContains(", skipping execution ...", b1);
        j.assertLogNotContains("Running body", b1);
    }

    @Test
    // @Issue("JENKINS-XXXXX")
    public void multipleLocksFillVariablesWithProperties() throws Exception {
        LockableResourcesManager.get()
                .createResourceWithLabelAndProperties("resource1", "label1", ImmutableMap.of("MYKEY", "MYVAL1"));
        LockableResourcesManager.get()
                .createResourceWithLabelAndProperties("resource2", "label1", ImmutableMap.of("MYKEY", "MYVAL2"));
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "lock(label: 'label1', variable: 'someVar', quantity: 2) {\n"
                        + "  echo \"$env.someVar0 HAS MYKEY=$env.someVar0_MYKEY\"\n"
                        + "  echo \"$env.someVar1 HAS MYKEY=$env.someVar1_MYKEY\"\n"
                        + "  echo \"$env.someVar2 HAS MYKEY=$env.someVar2_MYKEY\"\n"
                        + "}",
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        // Variable should have been filled
        j.assertLogContains("resource1 HAS MYKEY=MYVAL1", b1);
        j.assertLogContains("resource2 HAS MYKEY=MYVAL2", b1);
        j.assertLogContains("null HAS MYKEY=null", b1);
    }
}
