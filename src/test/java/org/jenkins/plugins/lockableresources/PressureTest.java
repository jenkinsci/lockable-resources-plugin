package org.jenkins.plugins.lockableresources;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.WithTimeout;

public class PressureTest extends LockStepTestBase {

  private static final Logger LOGGER = Logger.getLogger(LockStepTest.class.getName());

  @Rule public JenkinsRule j = new JenkinsRule();

  /**
   * Pressure test to lock resources via labels, resource name, ephemeral ... It simulates big
   * system with many chaotic locks. Hopefully it runs always good, because any analysis here will
   * be very hard.
   */
  @Test
  @WithTimeout(600)
  public void pressure10EnableSave() throws Exception {
    pressure(10);
  }
  @Test
  @WithTimeout(600)
  public void pressure10DisableSave() throws Exception {
    System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    pressure(10);
  }

  // @Test
  // @WithTimeout(600)
  // public void pressure20() throws Exception {
  //   pressure(20);
  //   System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
  //   pressure(20);
  // }

  // @Test
  // @WithTimeout(600)
  // public void pressure50() throws Exception {
  //   pressure(50);
  //   System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
  //   pressure(50);
  // }

  // @Test
  // @WithTimeout(600)
  // public void pressure70() throws Exception {
  //   pressure(70);
  //   System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
  //   pressure(70);
  // }

  // @Test
  // @WithTimeout(600)
  // public void pressure100() throws Exception {
  //   pressure(100);
  //   System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
  //   pressure(100);
  // }

  private void pressure(final int resourcesCount) throws Exception {
    System.setProperty(Constants.SYSTEM_PROPERTY_ENABLE_NODE_MIRROR, "true");
    LockableResourcesManager lrm = LockableResourcesManager.get();

    for (int i = 1; i <= resourcesCount; i++) {
      lrm.createResourceWithLabel("resourceA_" + Integer.toString(i), "label1 label2");
      lrm.createResourceWithLabel("resourceAA_" + Integer.toString(i), "label");
      lrm.createResourceWithLabel("resourceAAA_" + Integer.toString(i), "Label1");
      lrm.createResourceWithLabel("resourceAAAA_" + Integer.toString(i), "(=%/!(/)?$/ HH( RU))");
    }

    String pipeCode = "def stages = [:];\n";

    pipeCode +=
        "for(int i = 1; i < "
            + resourcesCount
            + "; i++) {\n"
            + "  String stageName = 'stage_' + i;\n"
            + "  stages[stageName] = {\n"
            + "    echo 'my stage: ' + stageName;\n"
            + "    echo 'test: label1 && label2 at ' + i;\n"
            + "    lock(label: 'label1 && label2', variable: 'someVar', quantity : 1) {\n"
            + "      echo \"VAR-1 IS $env.someVar\"\n"
            + "    }\n"
            + "    echo 'test: label1 || label2 at ' + i;\n"
            + "    lock(label: 'label1 || label2', variable: 'someVar', quantity : 2, resourceSelectStrategy: 'random') {\n"
            + "      echo \"VAR-2 IS $env.someVar\"\n"
            + "    }\n"
            + "    echo 'test: label2 at ' + i;\n"
            + "    lock(label: 'label2', variable: 'someVar', quantity : 5) {\n"
            + "      echo \"VAR-3 IS $env.someVar\"\n"
            + "    }\n"
            + "    echo 'test: resource_ephemeral_' + stageName;\n"
            + "    lock('resource_ephemeral_' + stageName) {\n"
            + "      echo \"locked resource_ephemeral_\" + stageName\n"
            + "    }\n"
            + "    echo 'test: resourceA_' + i;\n"
            + "    lock('resourceA_' + i) {\n"
            + "      echo \"locked resourceA_\" + i\n"
            + "    }\n"
            + "  }\n"
            + "}\n";

    pipeCode += "parallel stages;";

    lrm.reserve(Collections.singletonList(lrm.fromName("resourceA_1")), "test");

    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(new CpsFlowDefinition(pipeCode, true));
    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

    List<WorkflowRun> otherBuilds = new ArrayList<>();

    for (int i = 2; i <= resourcesCount; i++) {
      WorkflowJob p2 = j.jenkins.createProject(WorkflowJob.class, "p" + i);
      p2.setDefinition(new CpsFlowDefinition(pipeCode, true));
      WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
      otherBuilds.add(b2);
    }


    for (int i = 1; i <= resourcesCount; i++) {
      lrm.createResourceWithLabel("resourceB_" + Integer.toString(i), "label1");
    }

    j.waitForMessage("is locked, waiting...", b1);

    for (int i = 1; i <= resourcesCount; i++) {
      j.createSlave("AgentAAA_" + i, "label label2", null);
      lrm.createResourceWithLabel("resourceC_" + Integer.toString(i), "label1");
      j.createSlave("AGENT_BBB_" + i, null, null);
    }

    lrm.unreserve(Collections.singletonList(lrm.fromName("resourceA_1")));

    for (int i = 1; i <= resourcesCount; i++) {
      lrm.createResourceWithLabel("resourceD_" + Integer.toString(i), "label1");
    }

    j.assertBuildStatusSuccess(j.waitForCompletion(b1));
    for(WorkflowRun b2 : otherBuilds) {
      j.assertBuildStatusSuccess(j.waitForCompletion(b2));
    }
   }
}
