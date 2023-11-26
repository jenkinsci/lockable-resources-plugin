package org.jenkins.plugins.lockableresources;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class ConcurrentModificationExceptionTest {

    private static final Logger LOGGER = Logger.getLogger(ConcurrentModificationExceptionTest.class.getName());

    @Rule
    public final JenkinsRule j = new JenkinsRule();

    @Test
    public void parallelTasksTest() throws Exception {
        // disable save. Everything is saved into filesystem and it takes a while
        // normally it is no problem, but we need to starts many tasks parallel
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");

        // Do not mirror nodes now. We will allow it later in parallel tasks
        System.setProperty(Constants.SYSTEM_PROPERTY_ENABLE_NODE_MIRROR, "false");
        LOGGER.info("add agents");
        for (int i = 1; i <= 50; i++) j.createSlave("Agent_" + i, "label label2", null);
        LOGGER.info("add agents done");

        LockableResourcesManager LRM = LockableResourcesManager.get();

        LOGGER.info("add resources");
        for (int i = 1; i <= 500; i++) LRM.createResourceWithLabel("resource_" + i, "label label2");
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
                NodesMirror.createNodeResources();
                LOGGER.info("NodesMirror done");
            }
        };
        TimerTask taskCreateAgents = new TimerTask() {
            public void run() {
                LOGGER.info("create extra slaves");
                for (int i = 1; i <= 50; i++) {
                    try {
                        j.createSlave("ExtraAgent_" + i, "label label2", null);
                        j.jenkins.removeNode(j.jenkins.getNode("Agent_" + i));
                        Thread.sleep(5);
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
                for (int i = 1; i <= 500; i++) {

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

        // all the tasks are asynchronous operations, so wait until resources are created.
        LOGGER.info("wait for resources");
        for (int i = 1; i <= 100; i++) {
            Thread.sleep(500);
            LOGGER.info("wait for resources " + i + " " + LRM.resourceExist("ExtraResource_500") + " "
                    + LRM.resourceExist("ExtraAgent_50") + " " + LRM.resourceExist("Agent_100"));
            if (LRM.resourceExist("ExtraResource_500")
                    && LRM.resourceExist("ExtraAgent_50")
                    && !LRM.resourceExist("Agent_50")) break;
        }

        assertNotNull(LockableResourcesManager.get().fromName("ExtraResource_500"));
        assertNotNull(LockableResourcesManager.get().fromName("ExtraAgent_50"));
        assertNull(LockableResourcesManager.get().fromName("Agent_50"));
    }
}
