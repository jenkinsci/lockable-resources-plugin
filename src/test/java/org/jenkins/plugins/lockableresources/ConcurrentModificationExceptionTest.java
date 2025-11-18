package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.Functions;

import java.lang.Math;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ConcurrentModificationExceptionTest {

    private static final Logger LOGGER = Logger.getLogger(ConcurrentModificationExceptionTest.class.getName());

    // Prepare to capture CME clues in JVM or Jenkins instance logs
    // (sometimes the problem is reported there, but does not cause
    // a crash for any of the runs).
    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog();

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
     * a fix and non-regression.<br/>
     *
     * Alas, "reliably catching a non-deterministic race condition"
     * is maybe an oxymoron in itself, so we try to do our best here.
     *
     * @throws Exception  If test failed
     */
    @Test
    @Timeout(900)
    void noCmeWhileSavingXStreamVsLockedResourcesBuildAction(JenkinsRule j) throws Exception {
        // How many parallel stages would we use before saving WorkflowRun
        // state inside the pipeline run, and overall?
        // The preflood also defines how many lockable resources we use
        // (half persistent, half ephemeral).
        int preflood = 25, maxflood = 75;

        // More workers to increase the chaos in competition for resources
        int extraAgents = 16;

        // How many jobs run in parallel?
        // Note that along with the amount of agents and maxflood
        // this dictates how long the test runs.
        int maxRuns = 3;
        List<WorkflowJob> wfJobs = new ArrayList<>();
        List<WorkflowRun> wfRuns = new ArrayList<>();

        LockableResourcesManager lrm = LockableResourcesManager.get();

        // Prepare to capture CME clues in JVM or Jenkins instance logs
        // (sometimes the problem is reported there, but does not cause
        // a crash for any of the runs).
        Logger capturingLogger = Logger.getLogger(""); // root, or specific package/logger
        StringBuilder capturedLogs = new StringBuilder();
        Handler capturingLogHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
              capturedLogs.append(record.getLevel())
                .append(": ")
                .append(record.getMessage())
                .append('\n');
            }
            @Override public void flush() {}
            @Override public void close() throws SecurityException {}
        };

        // Involve also the lag and race of remote executions
        LOGGER.info("create extra build agents");
        for (int i = 1; i <= extraAgents; i++) {
            try {
                j.createSlave("ExtraAgent_" + i, "label label2 extra-agent", null);
            } catch (Exception error) {
                LOGGER.warning(error.toString());
            }
        }
        LOGGER.info("create extra build agents done");

        LOGGER.info("define " + (preflood / 2) + " persistent lockable resources");
        for (int i = 1; i <= preflood / 2; i++) {
            lrm.createResource("lock-" + i);
        }

        LOGGER.info("define " + maxRuns + " test workflows");
        String pipeCode =
                "import java.lang.Math;\n" +
                "import java.util.Random;\n" +
                // Do not occupy all readers at first, so all our
                // jobs can get defined and started simultaneously
                // (avoid them "waiting for available executors")
                "lock('first') { sleep 3 }\n" +
                "def parstages = [:]\n" +
                // flood with lock actions, including logging about them
                "def preflood = " + preflood + "\n" +
                "def maxflood = " + maxflood + "\n" +
                "for (int i = 1; i < preflood; i++) {\n" +
                // Note that we must use toString() and explicit vars to
                // avoid seeing same values at time of GString evaluation
                "  String iStr = String.valueOf(i)\n" +
                "  parstages[\"stage-${iStr}\".toString()] = {\n" +
                "    node() {\n" +
                "      lock(\"lock-${iStr}\".toString()) {\n" +
                "        sleep 1\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}\n" +
                // force a save while mutations are happening
                "parstages['saver'] = {\n" +
                "  org.jenkinsci.plugins.workflow.job.WorkflowRun r = currentBuild.rawBuild\n" +
                "  r.save()\n" +
                "}\n" +
                // sandwiching makes it more likely to get the race condition
                // as someone works with locks while XStream kicks in
                // Also make it actually wait for some (ephemeral) locks
                "for (int i = preflood; i < maxflood; i++) {\n" +
                "  String iStr = String.valueOf(i)\n" +
                "  String iStrLock = String.valueOf((i % preflood) + 1)\n" +
                "  String iStrName = \"In parstage ${iStr} for lock ${iStrLock}\".toString()\n" +
                "  parstages[\"stage-${iStr}\".toString()] = {\n" +
                "    node() {\n" +
                "      lock(\"lock-${iStrLock}\".toString()) {\n" +
                // Changes of currentBuild should cause some saves too
                // (also badges, SCM steps, etc. - but these would need
                // more plugins as dependencies just for the tests):
                "        echo \"Set currentBuild.displayName = '${iStrName}'\"\n" +
                "        currentBuild.displayName = iStrName\n" +
                // Randomize which job/lock combo waits for which,
                // so we do not have all builds sequentially completing:
                "        sleep (time: 500 + Math.abs(new Random().nextInt(1000)), unit: 'MILLISECONDS')\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}\n" +
                "parallel parstages\n";

        capturingLogger.addHandler(capturingLogHandler);
        try {
            for (int i = 0; i < maxRuns; i++) {
                WorkflowJob p = j.createProject(WorkflowJob.class);
                p.setDefinition(new CpsFlowDefinition(pipeCode, false));
                wfJobs.add(p);
            }

            LOGGER.info("Execute test workflows");
            for (int i = 0; i < maxRuns; i++) {
                WorkflowRun r = wfJobs.get(i).scheduleBuild2(0).waitForStart();
                wfRuns.add(r);
            }

            for (int i = 0; i < maxRuns; i++) {
                j.waitForMessage("[Pipeline] parallel", wfRuns.get(i));
            }

            // Trigger Jenkins-wide save activities.
            // Note: job runs also save workflow for good measure
            // FIXME: Save state of whole Jenkins config somehow?
            //  Is there more to XStream-able state to save?
            for (int i = 0; i < 10; i++) {
                LOGGER.info("Trigger Jenkins/LR state save (random interval ~3s +- 50ms)");
                lrm.save();
                // Let the timing be out of sync of ~1s sleeps of the pipelines
                Thread.sleep(2950 + Math.abs(new Random().nextInt(100)));
            }

            for (int i = 0; i < 10; i++) {
                LOGGER.info("Trigger Jenkins/LR state save (regular interval ~2.1s)");
                lrm.save();
                // Let the timing be out of sync of ~1s sleeps of the pipelines
                Thread.sleep(2139);
            }

            LOGGER.info("Wait for builds to complete");
            for (int i = 0; i < maxRuns; i++) {
                j.assertBuildStatusSuccess(j.waitForCompletion(wfRuns.get(i)));
            }
        } finally {
            // Complete this bit of ritual even if test run
            // (e.g. build status assertion) throws above
            capturingLogger.removeHandler(capturingLogHandler);
        }

        LOGGER.info("Check build logs that CME related messages are absent");
        List<String> indicatorsCME = new ArrayList<>();
        indicatorsCME.add("Failed to serialize");
        indicatorsCME.add("java.util.ConcurrentModificationException");

        for (int i = 0; i < maxRuns; i++) {
            WorkflowRun r = wfRuns.get(i);
            for (String s: indicatorsCME) {
                j.assertLogNotContains(s, r);
            }
        }

        // Not printed if assertion above fails:
        LOGGER.info("All " + maxRuns + " builds are done successfully and did not report CME");

        LOGGER.info("Check JVM stderr that CME related messages are absent");
        String stderr = systemErrRule.getLog();
        for (String s: indicatorsCME) {
            assertFalse(stderr.contains(s));
        }

        LOGGER.info("Check custom Jenkins logger that CME related messages are absent");
        String capturedLog = capturedLogs.toString();
        for (String s: indicatorsCME) {
            assertFalse(capturedLog.contains(s));
        }

        LOGGER.info("SUCCESS: Test completed without catching any indicators of ConcurrentModificationException");
    }
}
