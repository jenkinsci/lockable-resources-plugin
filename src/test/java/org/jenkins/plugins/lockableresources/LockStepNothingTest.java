package org.jenkins.plugins.lockableresources;

import hudson.model.Result;
import java.util.Objects;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LockStepNothingTest extends LockStepTestBase {

    private JenkinsRule jenkinsRule;

    @BeforeEach
    void setUp(JenkinsRule jenkinsRule) {
        this.jenkinsRule = jenkinsRule;

        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.setAllowEmptyOrNullValues(true);
    }

    @Test
    void lockNothingNotAllowed() throws Exception {
        WorkflowJob p = jenkinsRule.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                    timeout(time: 10, unit: 'SECONDS'){
                      lock() {
                        echo 'Nothing locked.'
                      }
                    }
                    echo 'Finish'""",
                true));
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.setAllowEmptyOrNullValues(false);

        WorkflowRun b1 = Objects.requireNonNull(p.scheduleBuild2(0)).waitForStart();
        jenkinsRule.assertBuildStatus(Result.FAILURE, jenkinsRule.waitForCompletion(b1));
        jenkinsRule.assertLogContains("Either resource label or resource name must be specified.", b1);
    }

    @Test
    void lockNothing() throws Exception {
        WorkflowJob p = jenkinsRule.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                    timeout(time: 10, unit: 'SECONDS'){
                      lock() {
                        echo 'Nothing locked.'
                      }
                      String tmp1 = null
                      lock(tmp1) {
                        echo 'Nothing locked. tmp1 variable is null.'
                      }
                      String tmp2 = ''
                      lock(tmp2) {
                        echo 'Nothing locked. tmp2 variable is empty.'
                      }
                    }
                    echo 'Finish'""",
                true));
        WorkflowRun b1 = Objects.requireNonNull(p.scheduleBuild2(0)).waitForStart();
        jenkinsRule.assertBuildStatus(Result.SUCCESS, jenkinsRule.waitForCompletion(b1));
        jenkinsRule.assertLogContains("Trying to acquire lock on [nothing]", b1);
        jenkinsRule.assertLogContains("Lock acquired on [nothing]", b1);
        jenkinsRule.assertLogContains("Nothing locked.", b1);
        jenkinsRule.assertLogContains("Nothing locked. tmp1 variable is null", b1);
        jenkinsRule.assertLogContains("Nothing locked. tmp2 variable is empty.", b1);
        jenkinsRule.assertLogContains("Lock released on resource [nothing]", b1);
    }

    @Test
    void lockNothingOnEmptyLabel() throws Exception {
        WorkflowJob p = jenkinsRule.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                    timeout(time: 10, unit: 'SECONDS'){
                      lock(label:'') {
                        echo 'Nothing locked.'
                      }
                    }
                    echo 'Finish'""",
                true));
        WorkflowRun b1 = Objects.requireNonNull(p.scheduleBuild2(0)).waitForStart();
        jenkinsRule.assertBuildStatus(Result.SUCCESS, jenkinsRule.waitForCompletion(b1));
        jenkinsRule.assertLogContains("Trying to acquire lock on [nothing]", b1);
        jenkinsRule.assertLogContains("Lock acquired on [nothing]", b1);
        jenkinsRule.assertLogContains("Nothing locked.", b1);
        jenkinsRule.assertLogContains("Lock released on resource [nothing]", b1);
    }

    @Test
    void lockNothingNullLabel() throws Exception {
        WorkflowJob p = jenkinsRule.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                    timeout(time: 10, unit: 'SECONDS'){
                      lock(label:null) {
                        echo 'Nothing locked.'
                      }
                    }
                    echo 'Finish'""",
                true));
        WorkflowRun b1 = Objects.requireNonNull(p.scheduleBuild2(0)).waitForStart();
        jenkinsRule.assertBuildStatus(Result.SUCCESS, jenkinsRule.waitForCompletion(b1));
        jenkinsRule.assertLogContains("Trying to acquire lock on [nothing]", b1);
        jenkinsRule.assertLogContains("Lock acquired on [nothing]", b1);
        jenkinsRule.assertLogContains("Nothing locked.", b1);
        jenkinsRule.assertLogContains("Lock released on resource [nothing]", b1);
    }

    @Test
    void lockNothingOnEmptyResource() throws Exception {
        WorkflowJob p = jenkinsRule.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                    timeout(time: 10, unit: 'SECONDS'){
                      lock(resource: '') {
                        echo 'Nothing locked.'
                      }
                    }
                    echo 'Finish'""",
                true));
        WorkflowRun b1 = Objects.requireNonNull(p.scheduleBuild2(0)).waitForStart();
        jenkinsRule.assertBuildStatus(Result.SUCCESS, jenkinsRule.waitForCompletion(b1));
        jenkinsRule.assertLogContains("Trying to acquire lock on [nothing]", b1);
        jenkinsRule.assertLogContains("Lock acquired on [nothing]", b1);
        jenkinsRule.assertLogContains("Nothing locked.", b1);
        jenkinsRule.assertLogContains("Lock released on resource [nothing]", b1);
    }

    @Test
    void lockNothingOnNullResource() throws Exception {
        WorkflowJob p = jenkinsRule.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                    timeout(time: 10, unit: 'SECONDS'){
                      lock(resource: null) {
                        echo 'Nothing locked.'
                      }
                    }
                    echo 'Finish'""",
                true));
        WorkflowRun b1 = Objects.requireNonNull(p.scheduleBuild2(0)).waitForStart();
        jenkinsRule.assertBuildStatus(Result.SUCCESS, jenkinsRule.waitForCompletion(b1));
        jenkinsRule.assertLogContains("Trying to acquire lock on [nothing]", b1);
        jenkinsRule.assertLogContains("Lock acquired on [nothing]", b1);
        jenkinsRule.assertLogContains("Nothing locked.", b1);
        jenkinsRule.assertLogContains("Lock released on resource [nothing]", b1);
    }

    @Test
    void lockNothingOnEmptyExtra() throws Exception {
        WorkflowJob p = jenkinsRule.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                    timeout(time: 10, unit: 'SECONDS'){
                      lock(extra: []) {
                        echo 'Nothing locked.'
                      }
                    }
                    echo 'Finish'""",
                true));
        WorkflowRun b1 = Objects.requireNonNull(p.scheduleBuild2(0)).waitForStart();
        jenkinsRule.assertBuildStatus(Result.SUCCESS, jenkinsRule.waitForCompletion(b1));
        jenkinsRule.assertLogContains("Trying to acquire lock on [nothing]", b1);
        jenkinsRule.assertLogContains("Lock acquired on [nothing]", b1);
        jenkinsRule.assertLogContains("Nothing locked.", b1);
        jenkinsRule.assertLogContains("Lock released on resource [nothing]", b1);
    }

    @Test
    void lockNothingOnNullExtra() throws Exception {
        WorkflowJob p = jenkinsRule.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                    timeout(time: 10, unit: 'SECONDS'){
                      lock(extra: null) {
                        echo 'Nothing locked.'
                      }
                    }
                    echo 'Finish'""",
                true));
        WorkflowRun b1 = Objects.requireNonNull(p.scheduleBuild2(0)).waitForStart();
        jenkinsRule.assertBuildStatus(Result.SUCCESS, jenkinsRule.waitForCompletion(b1));
        jenkinsRule.assertLogContains("Trying to acquire lock on [nothing]", b1);
        jenkinsRule.assertLogContains("Lock acquired on [nothing]", b1);
        jenkinsRule.assertLogContains("Nothing locked.", b1);
        jenkinsRule.assertLogContains("Lock released on resource [nothing]", b1);
    }
}
