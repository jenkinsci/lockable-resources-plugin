package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.Functions;

import java.util.ConcurrentModificationException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ConcurrentModificationExceptionTest {

    private static final Logger LOGGER = Logger.getLogger(ConcurrentModificationExceptionTest.class.getName());

    @Test
    void parallelTasksTest(JenkinsRule j) throws Exception {

        final int agentsCount = Functions.isWindows() ? 5 : 10;
        final int extraAgentsCount = Functions.isWindows() ? 5 : 20;
        final int resourcesCount = 100;
        final int extraResourcesCount = 100;

        // disable save. Everything is saved into filesystem, and it takes a while
        // normally it is no problem, but we need to start many tasks parallel
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");

        // Do not mirror nodes now. We will allow it later in parallel tasks
        System.setProperty(Constants.SYSTEM_PROPERTY_ENABLE_NODE_MIRROR, "false");
        LOGGER.info("add agents");
        for (int i = 1; i <= agentsCount; i++) j.createSlave("Agent_" + i, "label label2 agent", null);
        LOGGER.info("add agents done");

        LockableResourcesManager LRM = LockableResourcesManager.get();

        LOGGER.info("add resources");
        for (int i = 1; i <= resourcesCount; i++) LRM.createResourceWithLabel("resource_" + i, "label label2");
        LOGGER.info("add resources done");

        TimerTask taskBackwardCompatibility = new TimerTask() {
            public void run() {
                LOGGER.info("run BackwardCompatibility");
                BackwardCompatibility.compatibilityMigration();
                LOGGER.info("BackwardCompatibility done");
            }
        };
        TimerTask taskFreeDeadJobs = new TimerTask() {
            public void run() {
                LOGGER.info("run FreeDeadJobs");
                FreeDeadJobs.freePostMortemResources();
                LOGGER.info("FreeDeadJobs done");
            }
        };
        TimerTask taskNodesMirror = new TimerTask() {
            public void run() {
                System.setProperty(Constants.SYSTEM_PROPERTY_ENABLE_NODE_MIRROR, "true");
                LOGGER.info("run NodesMirror");
                org.jenkins.plugins.lockableresources.NodesMirror.createNodeResources();
                LOGGER.info("NodesMirror done");
            }
        };
        TimerTask taskCreateAgents = new TimerTask() {
            public void run() {
                LOGGER.info("create extra slaves");
                for (int i = 1; i <= extraAgentsCount; i++) {
                    try {
                        j.createSlave("ExtraAgent_" + i, "label label2 extra-agent", null);
                    } catch (Exception error) {
                        LOGGER.warning(error.toString());
                    }
                }
                LOGGER.info("create extra slaves done");
            }
        };
        TimerTask taskCreateResources = new TimerTask() {
            public void run() {
                LOGGER.info("create extra resources");
                for (int i = 1; i <= extraResourcesCount; i++) {

                    try {
                        if (LockableResourcesManager.get()
                                .createResourceWithLabel("ExtraResource_" + i, "label label2"))
                            LOGGER.info("ExtraResource_" + i + " added");
                        assertNotNull(LockableResourcesManager.get().fromName("ExtraResource_" + i));
                        Thread.sleep(10);
                    } catch (Exception error) {
                        LOGGER.warning(error.toString());
                    }
                }
                LOGGER.info("create extra resources done");
            }
        };
        long delay = 10L;
        Timer timerCreateAgents = new Timer("CreateAgents");
        timerCreateAgents.schedule(taskCreateAgents, delay);
        Timer timerCreateResources = new Timer("CreateResources");
        timerCreateResources.schedule(taskCreateResources, ++delay);
        Timer timerBackwardCompatibility = new Timer("BackwardCompatibility");
        timerBackwardCompatibility.schedule(taskBackwardCompatibility, ++delay);
        Timer timerFreeDeadJobs = new Timer("FreeDeadJobs");
        timerFreeDeadJobs.schedule(taskFreeDeadJobs, ++delay);
        Timer timerNodesMirror = new Timer("NodesMirror");
        timerNodesMirror.schedule(taskNodesMirror, ++delay);

        for (int i = 1; i <= 100; i++) {
            Thread.sleep(500);
            LOGGER.info("wait for resources " + i + " "
                    + " extra-agent: "
                    + LRM.getResourcesWithLabel("extra-agent").size() + " == " + extraAgentsCount);
            if (LRM.getResourcesWithLabel("extra-agent").size() == extraAgentsCount) break;
        }

        for (int i = 1; i <= extraAgentsCount; i++) {
            try {
                assertNotNull(LockableResourcesManager.get().fromName("ExtraAgent_" + i));
                j.jenkins.removeNode(j.jenkins.getNode("ExtraAgent_" + i));
            } catch (Exception error) {
                LOGGER.warning(error.toString());
            }
        }

        // all the tasks are asynchronous operations, so wait until resources are created.
        LOGGER.info("wait for resources");
        for (int i = 1; i <= 100; i++) {
            Thread.sleep(500);
            LOGGER.info("wait for resources " + i + " "
                    + LRM.resourceExist("ExtraResource_" + extraResourcesCount)
                    + " extra-agent: "
                    + LRM.getResourcesWithLabel("extra-agent").size() + " == 0 "
                    + " agent: " + LRM.getResourcesWithLabel("agent").size());
            if (LRM.resourceExist("ExtraResource_" + extraResourcesCount)
                    && LRM.getResourcesWithLabel("extra-agent").isEmpty()
                    && LRM.getResourcesWithLabel("agent").size() == agentsCount) break;
        }

        // normally it is bad idea to make so much assertions, but we need verify if all works fine
        for (int i = 1; i <= resourcesCount; i++) {
            assertNotNull(LockableResourcesManager.get().fromName("resource_" + i));
        }
        for (int i = 1; i <= extraResourcesCount; i++) {
            assertNotNull(LockableResourcesManager.get().fromName("ExtraResource_" + i));
        }
        for (int i = 1; i <= extraAgentsCount; i++) {
            assertNull(LockableResourcesManager.get().fromName("ExtraAgent_" + i));
        }
        for (int i = 1; i <= agentsCount; i++) {
            assertNotNull(LockableResourcesManager.get().fromName("Agent_" + i));
        }
    }

    /**
     * Various events in Jenkins can cause saving of Workflow state,
     * even if particular jobs are configured for "performance" mode
     * to avoid causing this themselves in transitions between steps.
     * This causes an XStream export of Java objects, which may crash
     * with a {@link ConcurrentModificationException} if certain
     * complex properties of Jenkins Actions are being modified at
     * the same time (e.g. LR log list is updated here):
     * <pre>
     * java.util.ConcurrentModificationException
     * ...
     * Caused: java.lang.RuntimeException: Failed to serialize
     *      org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction#logs
     *      for class org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction
     * ...
     * Also:   org.jenkinsci.plugins.workflow.actions.ErrorAction$ErrorId: {UUID}
     * Caused: java.lang.RuntimeException: Failed to serialize hudson.model.Actionable#actions
     *      for class org.jenkinsci.plugins.workflow.job.WorkflowRun
     * ...
     * </pre>
     *
     * This test aims to reproduce the issue, and eventually confirm
     * a fix and non-regression.
     *
     * @throws Exception  If test failed
     */
    @Test
    void noCmeWhileSavingXStreamVsLockedResourcesBuildAction(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(
            "lock ('temp-lock') {\n" +
                "def act = currentBuild.rawBuild.getAction(org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction)\n" +
                "parallel a: { act.append('A'); sleep 1 }, b: { act.append('B'); sleep 1 }\n" +
                // force a save while mutations are happening
                "org.jenkinsci.plugins.workflow.job.WorkflowRun r = currentBuild.rawBuild\n" +
                "r.save()\n" +
            "}\n",
          false));
        j.buildAndAssertSuccess(p);
    }
}
