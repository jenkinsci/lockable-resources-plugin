/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.google.common.base.Joiner;
import hudson.model.Result;
import java.util.List;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for disabled resources ({@link LockableResource#getDisabledCause()}).
 */
@WithJenkins
class DisabledResourceTest {

    @BeforeEach
    void setUp() {
        // to speed up the test
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    }

    private static String m(String... lines) {
        return Joiner.on('\n').join(lines);
    }

    private static LockableResource resource(String name) {
        return LockableResourcesManager.get().fromName(name);
    }

    private static void disable(JenkinsRule j, String name, String cause) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(
                new CpsFlowDefinition("updateLock(resource: '" + name + "', disabledCause: '" + cause + "')", true));
        j.buildAndAssertSuccess(p);
    }

    private static void enable(JenkinsRule j, String name) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition("updateLock(resource: '" + name + "', disabledCause: null)", true));
        WorkflowRun b = j.buildAndAssertSuccess(p);
        j.assertLogContains("Resource [" + name + "] enabled.", b);
    }

    @Test
    void blankCauseMeansEnabled(JenkinsRule j) {
        LockableResource r = new LockableResource("resource1");
        assertThat(r.isDisabled(), is(false));
        r.setDisabledCause("  broken  ");
        assertThat(r.isDisabled(), is(true));
        assertThat(r.getDisabledCause(), is("broken"));
        r.setDisabledCause("   ");
        assertThat(r.isDisabled(), is(false));
        assertThat(r.getDisabledCause(), is(nullValue()));
    }

    @Test
    void disableAndEnableViaUpdateLock(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "updateLock(resource: 'resource1', disabledCause: \"Something goes wrong in ${env.BUILD_ID}\")", true));
        WorkflowRun b = j.buildAndAssertSuccess(p);
        j.assertLogContains("Resource [resource1] disabled: Something goes wrong in 1", b);
        assertThat(resource("resource1").isDisabled(), is(true));
        assertThat(resource("resource1").getDisabledCause(), is("Something goes wrong in 1"));

        enable(j, "resource1");
        assertThat(resource("resource1").isDisabled(), is(false));
    }

    @Test
    void updateLockWithoutDisabledCauseKeepsState(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");
        resource("resource1").setDisabledCause("broken");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("updateLock(resource: 'resource1', setNote: 'hello')", true));
        j.buildAndAssertSuccess(p);

        assertThat(resource("resource1").getDisabledCause(), is("broken"));
    }

    @Test
    void deleteWithDisabledCauseIsRejected(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "updateLock(resource: 'resource1', deleteResource: true, disabledCause: 'broken')", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b));
        assertThat(resource("resource1").isDisabled(), is(false));
    }

    @Test
    void lockByNameWaitsUntilEnabled(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");
        disable(j, "resource1", "broken");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock('resource1') { echo 'inside lock' }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Resource: resource1] is not free, waiting for execution ...", b);
        j.assertLogContains("The resource [resource1] is disabled: broken", b);
        assertThat(resource("resource1").isLocked(), is(false));
        assertThat(b.isBuilding(), is(true));

        enable(j, "resource1");

        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        j.assertLogContains("inside lock", b);
    }

    @Test
    void lockByLabelSkipsDisabledResource(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");
        disable(j, "resource1", "broken");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m("lock(label: 'label1', quantity: 1, variable: 'LOCKED') {", "  echo \"Locked: ${env.LOCKED}\"", "}"),
                true));
        WorkflowRun b = j.buildAndAssertSuccess(p);
        j.assertLogContains("Locked: resource2", b);
        j.assertLogNotContains("Locked: resource1", b);
    }

    @Test
    void lockByLabelWaitsWhenAllAreDisabled(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        disable(j, "resource1", "broken");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m("lock(label: 'label1', quantity: 1, variable: 'LOCKED') {", "  echo \"Locked: ${env.LOCKED}\"", "}"),
                true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Label: label1, Quantity: 1] is not free, waiting for execution ...", b);
        assertThat(b.isBuilding(), is(true));

        enable(j, "resource1");

        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        j.assertLogContains("Locked: resource1", b);
    }

    @Test
    void disablingLockedResourceDoesNotBreakRunningBuild(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        WorkflowJob p1 = j.createProject(WorkflowJob.class, "p1");
        p1.setDefinition(
                new CpsFlowDefinition("lock('resource1') { semaphore 'wait-inside'; echo 'still running' }", true));
        WorkflowRun b1 = p1.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-inside/1", b1);

        disable(j, "resource1", "broken");
        assertThat(resource("resource1").isDisabled(), is(true));
        assertThat(resource("resource1").getBuild(), is(b1));

        // a second build waits, also after the first one has released the resource
        WorkflowJob p2 = j.createProject(WorkflowJob.class, "p2");
        p2.setDefinition(new CpsFlowDefinition("lock('resource1') { echo 'second inside lock' }", true));
        WorkflowRun b2 = p2.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b2);

        SemaphoreStep.success("wait-inside/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogContains("still running", b1);
        j.assertLogContains("Lock released on resource [Resource: resource1]", b1);

        assertThat(resource("resource1").isLocked(), is(false));
        assertThat(resource("resource1").isDisabled(), is(true));
        assertThat(b2.isBuilding(), is(true));
        j.assertLogNotContains("second inside lock", b2);

        enable(j, "resource1");
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        j.assertLogContains("second inside lock", b2);
    }

    @Test
    void globalConfigSaveKeepsDisabledCause(JenkinsRule j) throws Exception {
        LockableResource r = new LockableResource("resource1");
        r.setDisabledCause("broken");
        LockableResourcesManager.get().setDeclaredResources(List.of(r));

        j.configRoundtrip();

        assertThat(resource("resource1").getDisabledCause(), is("broken"));
    }

    @Test
    void declarativePipeline(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        LockableResourcesManager.get().createResourceWithLabel("resource2", "label1");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m(
                        "pipeline {",
                        " agent none",
                        " stages {",
                        "  stage('disable') {",
                        "   steps {",
                        "    updateLock resource: 'resource1', disabledCause: \"Something goes wrong in ${env.BUILD_ID}\"",
                        "   }",
                        "  }",
                        "  stage('lock') {",
                        "   steps {",
                        "    lock(label: 'label1', resource: null, quantity: 1, variable: 'LOCKED') {",
                        "     echo \"Locked: ${env.LOCKED}\"",
                        "    }",
                        "   }",
                        "  }",
                        "  stage('enable') {",
                        "   steps {",
                        "    updateLock resource: 'resource1', disabledCause: null",
                        "   }",
                        "  }",
                        " }",
                        "}"),
                true));
        WorkflowRun b = j.buildAndAssertSuccess(p);
        j.assertLogContains("Resource [resource1] disabled: Something goes wrong in 1", b);
        j.assertLogContains("Locked: resource2", b);
        j.assertLogContains("Resource [resource1] enabled.", b);
        assertThat(resource("resource1").isDisabled(), is(false));
    }
}
