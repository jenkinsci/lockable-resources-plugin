package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertFalse;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import java.util.Collections;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class LockStepWithRestartTest extends LockStepTestBase {

    private static final Logger LOGGER = Logger.getLogger(LockStepTestBase.class.getName());

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void lockOrderRestart() throws Throwable {
        sessions.then(j -> {
            LockableResourcesManager.get().createResource("resource1");
            WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("""
                    lock('resource1') {
                      semaphore 'wait-inside-lockOrderRestart'
                    }
                    echo 'Finish'""", true));
            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait-inside-lockOrderRestart/1", b1);
            WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
            // Ensure that b2 reaches the lock before b3
            j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b2);
            isPaused(b2, 1, 1);
            WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
            // Both 2 and 3 are waiting for locking resource1

            j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b3);
            isPaused(b3, 1, 1);
        });

        sessions.then(j -> {
            WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b1 = p.getBuildByNumber(1);
            WorkflowRun b2 = p.getBuildByNumber(2);
            WorkflowRun b3 = p.getBuildByNumber(3);

            // Unlock resource1
            SemaphoreStep.success("wait-inside-lockOrderRestart/1", null);
            j.waitForMessage("Lock released on resource [Resource: resource1]", b1);
            isPaused(b1, 1, 0);

            j.waitForMessage("Trying to acquire lock on [Resource: resource1]", b2);
            isPaused(b2, 1, 0);
            j.assertLogContains("[resource1] is locked by build " + b1.getFullDisplayName(), b3);
            isPaused(b3, 1, 1);
            SemaphoreStep.success("wait-inside-lockOrderRestart/2", null);
            SemaphoreStep.waitForStart("wait-inside-lockOrderRestart/3", b3);
            j.assertLogContains("Trying to acquire lock on [Resource: resource1]", b3);
            SemaphoreStep.success("wait-inside-lockOrderRestart/3", null);
            j.waitForMessage("Finish", b3);
            isPaused(b3, 1, 0);
        });
    }

    @Test
    void interoperabilityOnRestart() throws Throwable {
        sessions.then(j -> {
            LockableResourcesManager.get().createResource("resource1");
            WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("""
                    lock('resource1') {
                      semaphore 'wait-inside-interoperabilityOnRestart'
                    }
                    echo 'Finish'""", true));
            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait-inside-interoperabilityOnRestart/1", b1);
            isPaused(b1, 1, 0);

            FreeStyleProject f = j.createFreeStyleProject("f");
            f.addProperty(new RequiredResourcesProperty("resource1", null, null, null, null));

            f.scheduleBuild2(0);
            TestHelpers.waitForQueue(j.jenkins, f);
        });

        sessions.then(j -> {
            WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
            FreeStyleProject f = j.jenkins.getItemByFullName("f", FreeStyleProject.class);
            WorkflowRun b1 = p.getBuildByNumber(1);

            // Unlock resource1
            SemaphoreStep.success("wait-inside-interoperabilityOnRestart/1", null);
            j.waitForMessage("Lock released on resource [Resource: resource1]", b1);
            isPaused(b1, 1, 0);

            FreeStyleBuild fb1;
            LOGGER.info("Waiting for freestyle #1 to start building");
            while ((fb1 = f.getBuildByNumber(1)) == null) {
                Thread.sleep(250);
            }

            j.waitForMessage("acquired lock on [resource1]", fb1);
            j.waitForMessage("Finish", b1);
            isPaused(b1, 1, 0);

            j.waitUntilNoActivity();
        });
    }

    @Test
    void testReserveOverRestart() throws Throwable {
        sessions.then(j -> {
            LockableResourcesManager manager = LockableResourcesManager.get();
            manager.createResource("resource1");
            manager.reserve(Collections.singletonList(manager.fromName("resource1")), "user");

            WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("""
                    lock('resource1') {
                      echo 'inside'
                    }
                    echo 'Finish'""", true));
            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
            j.waitForMessage("The resource [resource1] is reserved by user", b1);
            isPaused(b1, 1, 1);

            FreeStyleProject f = j.createFreeStyleProject("f");
            f.addProperty(new RequiredResourcesProperty("resource1", null, null, null, null));

            f.scheduleBuild2(0);
            TestHelpers.waitForQueue(j.jenkins, f);
        });

        sessions.then(j -> {
            WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b1 = p.getBuildByNumber(1);

            LockableResourcesManager manager = LockableResourcesManager.get();
            manager.createResource("resource1");
            manager.unreserve(Collections.singletonList(manager.fromName("resource1")));

            j.waitForMessage("Lock released on resource [Resource: resource1]", b1);
            isPaused(b1, 1, 0);
            j.waitForMessage("Finish", b1);
            isPaused(b1, 1, 0);

            j.waitUntilNoActivity();
        });
    }

    @Test
    void checkQueueAfterRestart() throws Throwable {
        sessions.then(j -> {
            LockableResourcesManager lrm = LockableResourcesManager.get();

            lrm.createResourceWithLabel("resource1", "label");
            lrm.createResourceWithLabel("resource2", "label");
            WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("lock(label: 'label', quantity: 1) { echo 'inside lock' }", true));

            j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
            j.jenkins.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy());

            lrm.reserve(Collections.singletonList(lrm.fromName("resource1")), "test");
            lrm.reserve(Collections.singletonList(lrm.fromName("resource2")), "test");

            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
            j.waitForMessage("The resource [resource1] is reserved by test.", b1);
        });

        sessions.then(j -> {
            LockableResourcesManager lrm = LockableResourcesManager.get();
            WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b1 = p.getBuildByNumber(1);

            j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
            j.jenkins.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy());

            JenkinsRule.WebClient wc = j.createWebClient();
            wc.login("user");

            TestHelpers testHelpers = new TestHelpers();
            testHelpers.clickButton("unreserve", "resource1");

            lrm.unreserve(Collections.singletonList(lrm.fromName("resource1")));

            assertFalse(lrm.fromName("resource1").isReserved());

            j.waitForMessage("Lock acquired on [Label: label, Quantity: 1]", b1);
            j.waitForMessage("Lock released on resource [Label: label, Quantity: 1]", b1);
        });
    }

    @Test
    void chaosOnRestart() throws Throwable {
        final int resourceCount = 50;
        sessions.then(j -> {
            for (int i = 1; i <= resourceCount; i++) {
                LockableResourcesManager.get().createResourceWithLabel("resource" + i, "label");
            }
            WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    "def stages = [:];\n"
                            + "for (int i = 1; i <= " + resourceCount + "; i++) {\n"
                            + "  def stageName = 'order' + i;\n"
                            + "  def resourceName = 'resource' + i;\n"
                            + "  def semId = 'wait-inside-lockOrderRestart-' + i;\n"
                            + "  stages[stageName] = {\n"
                            + "    lock('ephemeral_' + env.BUILD_NUMBER + '_' + resourceName) {\n"
                            + "      lock(resourceName) {semaphore semId}\n"
                            + "    }\n"
                            + "  }\n"
                            + "}\n"
                            + "parallel stages\n"
                            + "echo 'Finish'",
                    true));
            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait-inside-lockOrderRestart-" + resourceCount + "/1", b1);
            WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
            // Ensure that b2 reaches the lock before b3
            j.waitForMessage("[resource" + resourceCount + "] is locked", b2);
            WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
            // Both 2 and 3 are waiting for locking resource1
            j.waitForMessage("[resource" + resourceCount + "] is locked", b3);
        });

        sessions.then(j -> {
            WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b1 = p.getBuildByNumber(1);
            WorkflowRun b2 = p.getBuildByNumber(2);
            WorkflowRun b3 = p.getBuildByNumber(3);

            // Unlock resources
            for (int i = 1; i <= resourceCount; i++) {
                SemaphoreStep.success("wait-inside-lockOrderRestart-" + i + "/1", null);
            }
            j.waitForMessage("Lock released on resource [Resource: resource" + resourceCount + "]", b1);
            j.waitForMessage("Lock acquired on [Resource: resource" + resourceCount + "]", b2);
            j.assertLogContains("[resource" + resourceCount + "] is locked by build " + b1.getFullDisplayName(), b3);
            for (int i = 1; i <= resourceCount; i++) {
                SemaphoreStep.success("wait-inside-lockOrderRestart-" + i + "/2", null);
            }
            SemaphoreStep.waitForStart("wait-inside-lockOrderRestart-" + resourceCount + "/3", b3);
            j.assertLogContains("Lock acquired on [Resource: resource" + resourceCount + "]", b3);
            for (int i = 1; i <= resourceCount; i++) {
                SemaphoreStep.success("wait-inside-lockOrderRestart-" + i + "/3", null);
            }
            j.waitForMessage("Finish", b3);
        });
    }
}
