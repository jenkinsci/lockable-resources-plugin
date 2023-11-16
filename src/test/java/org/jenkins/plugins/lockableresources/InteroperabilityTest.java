package org.jenkins.plugins.lockableresources;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;

public class InteroperabilityTest extends LockStepTestBase {

    private static final Logger LOGGER = Logger.getLogger(InteroperabilityTest.class.getName());
    // ---------------------------------------------------------------------------
    @Before
    public void setUp() {
        // to speed up the test
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    }

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void interoperability() throws Exception {
        final Semaphore semaphore = new Semaphore(1);
        LockableResourcesManager.get().createResource("resource1");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(
                new CpsFlowDefinition("lock('resource1') {\n" + "	echo 'Locked'\n" + "}\n" + "echo 'Finish'", true));

        FreeStyleProject f = j.createFreeStyleProject("f");
        f.addProperty(new RequiredResourcesProperty("resource1", null, null, null, null));
        f.getBuildersList().add(new TestBuilder() {

            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws InterruptedException {
                semaphore.acquire();
                return true;
            }
        });
        semaphore.acquire();
        FreeStyleBuild f1 = f.scheduleBuild2(0).waitForStart();

        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        LOGGER.info("wait for: [resource1] is locked by " + f1.getFullDisplayName());
        j.waitForMessage("[resource1] is locked by " + f1.getFullDisplayName(), b1);
        isPaused(b1, 1, 1);
        semaphore.release();

        // Wait for lock after the freestyle finishes
        LOGGER.info("wait for2: Lock released on resource [resource1]");
        j.waitForMessage("Lock released on resource [resource1]", b1);
        isPaused(b1, 1, 0);
        j.assertBuildStatusSuccess(j.waitForCompletion(f1));
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
    }
}
