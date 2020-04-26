package org.jenkins.plugins.lockableresources;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;

public class InteroperabilityTest extends LockStepTestBase {

  @Rule public JenkinsRule j = new JenkinsRule();

  @Test
  public void interoperability() throws Exception {
    final Semaphore semaphore = new Semaphore(1);
    LockableResourcesManager.get().createResource("resource1");
    WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
    p.setDefinition(
        new CpsFlowDefinition(
            "lock('resource1') {\n" + "	echo 'Locked'\n" + "}\n" + "echo 'Finish'", true));

    FreeStyleProject f = j.createFreeStyleProject("f");
    f.addProperty(new RequiredResourcesProperty("resource1", null, null, null, null));
    f.getBuildersList()
        .add(
            new TestBuilder() {

              @Override
              public boolean perform(
                  AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                  throws InterruptedException, IOException {
                semaphore.acquire();
                return true;
              }
            });
    semaphore.acquire();
    FreeStyleBuild f1 = f.scheduleBuild2(0).waitForStart();

    WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
    j.waitForMessage("[resource1] is locked by " + f1.getFullDisplayName() + ", waiting...", b1);
    isPaused(b1, 1, 1);
    semaphore.release();

    // Wait for lock after the freestyle finishes
    j.waitForMessage("Lock released on resource [resource1]", b1);
    isPaused(b1, 1, 0);
  }
}
