package org.jenkins.plugins.lockableresources;

import hudson.Functions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PressureTest extends LockStepTestBase {

    private static final Logger LOGGER = Logger.getLogger(PressureTest.class.getName());

    /**
     * Pressure test to lock resources via labels, resource name, ephemeral ... It simulates big
     * system with many chaotic locks. Hopefully it runs always good, because any analysis here will
     * be very hard.
     */
    @Test
    // it depends on which node you are running
    @Timeout(900)
    void pressureEnableSave(JenkinsRule j) throws Exception {
        // keep in mind, that the windows nodes at jenkins-infra are not very fast
        pressure(j, Functions.isWindows() ? 10 : 10);
    }

    @Test
    @Timeout(900)
    void pressureDisableSave(JenkinsRule j) throws Exception {
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
        // keep in mind, that the windows nodes at jenkins-infra are not very fast
        pressure(j, Functions.isWindows() ? 10 : 10);
    }

    private static void pressure(JenkinsRule j, final int resourcesCount) throws Exception {
        // count of parallel jobs
        final int jobsCount = (resourcesCount * 2) + 1;
        final int nodesCount = (resourcesCount / 10) + 1;
        // enable node mirroring to make more chaos
        System.setProperty(Constants.SYSTEM_PROPERTY_ENABLE_NODE_MIRROR, "true");
        System.setProperty(Constants.SYSTEM_PROPERTY_PRINT_BLOCKED_RESOURCE, "0");
        LockableResourcesManager lrm = LockableResourcesManager.get();

        // create resources
        LOGGER.info("Create resources with labels");
        for (int i = 1; i <= resourcesCount; i++) {
            lrm.createResourceWithLabel("resourceA_" + i, "label1 label2");
            lrm.createResourceWithLabel("resourceAA_" + i, "label");
            lrm.createResourceWithLabel("resourceAAA_" + i, "label1");
            lrm.createResourceWithLabel("resourceAAAA_" + i, "(=%/!(/)?$/ HH( RU))");
        }

        // define groovy script used by our test jobs
        String pipeCode = "";

        pipeCode += "lock('initpipe') {echo 'initialized'};\n";

        pipeCode += "def stages = [:];\n";
        pipeCode += "for(int i = 1; i < "
                + resourcesCount
                + "; i++) {\n"
                + "  final int index = i;\n"
                + "  String stageName = 'stage_' + index;\n"
                + "  stages[stageName] = {\n"
                // + "    echo 'my stage: ' + stageName;\n"
                // + "    echo 'test: label1 && label2 at ' + index;\n"
                + "    lock(label: 'label1 && label2', variable: 'someVar', quantity : 1) {\n"
                // + "      echo \"*** VAR-1 IS $env.someVar\"\n"
                + "    }\n"
                // + "    echo 'test: label2 at ' + index;\n"
                + "    lock(label: 'label1', variable: 'someVar', quantity : 5) {\n"
                // + "      echo \"*** VAR-3 IS $env.someVar\"\n"
                + "    }\n"
                // + "    echo 'test: resource_ephemeral_' + stageName;\n"
                + "    lock('resource_ephemeral_' + stageName) {\n"
                // + "      echo \"*** locked resource_ephemeral_\" + stageName\n"
                + "    }\n"
                // + "    echo 'test: resourceA_' + index;\n"
                + "    lock('resourceA_' + index) {\n"
                // + "      echo \"*** locked resourceA_\" + index\n"
                + "    }\n"
                // recursive lock
                // do not activate it, this break down the execution time
                // and we need to speed up actions
                // + "    lock(label: 'label2', quantity : 1) {\n"
                // + "      lock(label: 'label2', quantity : 1, inversePrecedence : true) {\n"
                // + "        lock(label: 'label2', quantity : 4, skipIfLocked: true,
                // resourceSelectStrategy: 'random') {\n"
                // + "          lock('resource_ephemeral_' + stageName) {\n"
                // + "            lock('resourceA_' + index) {\n"
                // + "              echo \"inside recursive lock \" + stageName\n"
                // + "            }\n"
                // + "          }\n"
                // + "        }\n"
                // + "      }\n"
                // + "    }\n"
                + "  }\n"
                + "}\n";

        pipeCode += "parallel stages;";

        // reserve 'initpipe' resource to be sure that parallel builds and stages are paused at the same
        // time.
        LOGGER.info("Lock execution by 'initpipe'");
        lrm.createResourceWithLabel("initpipe", "sync step");
        lrm.reserve(Collections.singletonList(lrm.fromName("initpipe")), "test");

        // create first project and start the build
        LOGGER.info("Create project");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "main-project");
        p.setDefinition(new CpsFlowDefinition(pipeCode, true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage(", waiting for execution ...", b1);

        List<WorkflowRun> otherBuilds = new ArrayList<>();

        // create extra jobs to make more chaos
        LOGGER.info("Create extra projects");
        for (int i = 1; i <= jobsCount; i++) {
            WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "extra-project_" + i);
            p2.setDefinition(new CpsFlowDefinition(pipeCode, true));
            WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
            otherBuilds.add(b2);
            j.waitForMessage(", waiting for execution ...", b2);
        }

        // create more resources until the first job has been started
        LOGGER.info("Create more resources");
        for (int i = 1; i <= resourcesCount; i++) {
            lrm.createResourceWithLabel("resourceB_" + i, "label1");
        }

        // create jenkins nodes. All shall be mirrored to resources
        LOGGER.info("Create jenkins nodes");
        for (int i = 1; i <= nodesCount; i++) {
            j.createSlave("AgentAAA_" + i, "label label1 label2", null);
            lrm.createResourceWithLabel("resourceC_" + i, "label1");
            j.createSlave("AGENT_BBB_" + i, null, null);
        }

        // unreserve it now, and the fun may starts. Because all the parallel jobs and stages will be
        // "free"
        LOGGER.info("Start the chaos");
        lrm.unreserve(Collections.singletonList(lrm.fromName("initpipe")));

        // create more resources until the first job has been started
        LOGGER.info("Additional resources");
        for (int i = 1; i <= resourcesCount; i++) {
            lrm.createResourceWithLabel("resourceD_" + i, "label1");
        }

        // create more jenkins nodes to make more chaos
        LOGGER.info("Create slaves");
        for (int i = 1; i <= nodesCount; i++) {
            j.createSlave("AgentCCC_" + i, "label label1 label2", null);
            j.createSlave("AGENT_DDD_" + i, null, null);
        }

        // simulate chaos by user actions
        LOGGER.info("User action 'reserve'");
        for (int i = 1; i <= resourcesCount; i++) {
            lrm.reserve(Collections.singletonList(lrm.fromName("resourceA_" + i)), "test");
        }

        LOGGER.info("User action 'reserve' slaves");
        for (int i = 1; i <= nodesCount; i++) {
            lrm.reserve(Collections.singletonList(lrm.fromName("AgentCCC_" + i)), "test");
            lrm.reserve(Collections.singletonList(lrm.fromName("AGENT_DDD_" + i)), "test");
        }

        LOGGER.info("User action 'reassign', 'steal', 'unreserve'");
        for (int i = 1; i <= resourcesCount; i++) {
            lrm.reassign(Collections.singletonList(lrm.fromName("resourceA_" + i)), "second user");
            lrm.steal(Collections.singletonList(lrm.fromName("resourceAA_" + i)), "second user");
            lrm.unreserve(Collections.singletonList(lrm.fromName("resourceA_" + i)));
            lrm.unreserve(Collections.singletonList(lrm.fromName("resourceAA_" + i)));
        }

        LOGGER.info("User action 'unreserve' slaves");
        for (int i = 1; i <= nodesCount; i++) {
            lrm.unreserve(Collections.singletonList(lrm.fromName("AgentCCC_" + i)));
            lrm.unreserve(Collections.singletonList(lrm.fromName("AGENT_DDD_" + i)));
        }

        // wait until the first build has been stopped
        LOGGER.info("Wait for build b1");
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        LOGGER.info("build b1: done");

        // wait until all parallel jobs has been stopped
        for (WorkflowRun b2 : otherBuilds) {
            LOGGER.info("Wait for build " + b2.getAbsoluteUrl());
            j.assertBuildStatusSuccess(j.waitForCompletion(b2));
            LOGGER.info("build " + b2.getUrl() + ": done");
        }
    }
}
