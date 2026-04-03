/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.google.common.base.Joiner;
import hudson.model.Result;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for the {@link UpdateLockStep}.
 */
@WithJenkins
class UpdateLockStepTest {

    @BeforeEach
    void setUp() {
        // to speed up the test
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    }

    private static String m(String... lines) {
        return Joiner.on('\n').join(lines);
    }

    @Test
    void createResource(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m(
                        "node {",
                        "  updateLock(resource: 'newResource', createResource: true)",
                        "  echo 'Resource created'",
                        "}"),
                true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        j.assertBuildStatus(Result.SUCCESS, b);
        j.assertLogContains("Resource created", b);

        LockableResource resource = LockableResourcesManager.get().fromName("newResource");
        assertThat(resource, is(notNullValue()));
        assertThat(resource.isEphemeral(), is(false));
    }

    @Test
    void createResourceWithLabels(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m(
                        "node {",
                        "  updateLock(resource: 'newResource', createResource: true, setLabels: 'label1 label2')",
                        "}"),
                true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        j.assertBuildStatus(Result.SUCCESS, b);

        LockableResource resource = LockableResourcesManager.get().fromName("newResource");
        assertThat(resource, is(notNullValue()));
        assertThat(resource.getLabelsAsList(), containsInAnyOrder("label1", "label2"));
    }

    @Test
    void deleteResource(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("toDelete");
        assertThat(LockableResourcesManager.get().fromName("toDelete"), is(notNullValue()));

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m("node {", "  updateLock(resource: 'toDelete', deleteResource: true)", "}"), true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        j.assertBuildStatus(Result.SUCCESS, b);

        assertThat(LockableResourcesManager.get().fromName("toDelete"), is(nullValue()));
    }

    @Test
    void deleteNonExistentResourceSucceeds(JenkinsRule j) throws Exception {
        assertThat(LockableResourcesManager.get().fromName("doesNotExist"), is(nullValue()));

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m("node {", "  updateLock(resource: 'doesNotExist', deleteResource: true)", "}"), true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        // Should succeed even if resource doesn't exist
        j.assertBuildStatus(Result.SUCCESS, b);
    }

    @Test
    void setLabels(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "oldLabel1 oldLabel2");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m("node {", "  updateLock(resource: 'resource1', setLabels: 'newLabel1 newLabel2')", "}"), true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        j.assertBuildStatus(Result.SUCCESS, b);

        LockableResource resource = LockableResourcesManager.get().fromName("resource1");
        assertThat(resource.getLabelsAsList(), containsInAnyOrder("newLabel1", "newLabel2"));
    }

    @Test
    void addLabels(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "existingLabel");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m("node {", "  updateLock(resource: 'resource1', addLabels: 'newLabel1 newLabel2')", "}"), true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        j.assertBuildStatus(Result.SUCCESS, b);

        LockableResource resource = LockableResourcesManager.get().fromName("resource1");
        assertThat(resource.getLabelsAsList(), containsInAnyOrder("existingLabel", "newLabel1", "newLabel2"));
    }

    @Test
    void addDuplicateLabelsIgnored(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1 label2");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m("node {", "  updateLock(resource: 'resource1', addLabels: 'label1 label3')", "}"), true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        j.assertBuildStatus(Result.SUCCESS, b);

        LockableResource resource = LockableResourcesManager.get().fromName("resource1");
        assertThat(resource.getLabelsAsList(), containsInAnyOrder("label1", "label2", "label3"));
    }

    @Test
    void removeLabels(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1 label2 label3");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m("node {", "  updateLock(resource: 'resource1', removeLabels: 'label2')", "}"), true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        j.assertBuildStatus(Result.SUCCESS, b);

        LockableResource resource = LockableResourcesManager.get().fromName("resource1");
        assertThat(resource.getLabelsAsList(), containsInAnyOrder("label1", "label3"));
    }

    @Test
    void addAndRemoveLabels(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "keep remove1 remove2");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m(
                        "node {",
                        "  updateLock(resource: 'resource1', addLabels: 'new1 new2', removeLabels: 'remove1 remove2')",
                        "}"),
                true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        j.assertBuildStatus(Result.SUCCESS, b);

        LockableResource resource = LockableResourcesManager.get().fromName("resource1");
        assertThat(resource.getLabelsAsList(), containsInAnyOrder("keep", "new1", "new2"));
    }

    @Test
    void setNote(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m("node {", "  updateLock(resource: 'resource1', setNote: 'Test note from build')", "}"), true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        j.assertBuildStatus(Result.SUCCESS, b);

        LockableResource resource = LockableResourcesManager.get().fromName("resource1");
        assertThat(resource.getNote(), is("Test note from build"));
    }

    @Test
    void clearLabelsWithEmptySetLabels(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1 label2");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(
                new CpsFlowDefinition(m("node {", "  updateLock(resource: 'resource1', setLabels: '')", "}"), true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        j.assertBuildStatus(Result.SUCCESS, b);

        LockableResource resource = LockableResourcesManager.get().fromName("resource1");
        // Note: empty setLabels is treated as null and doesn't clear labels
        assertThat(resource.getLabelsAsList(), containsInAnyOrder("label1", "label2"));
    }

    @Test
    void failIfResourceNotExist(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m("node {", "  updateLock(resource: 'nonexistent', setLabels: 'label1')", "}"), true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        j.assertBuildStatus(Result.FAILURE, b);
        j.assertLogContains("does not exist", b);
    }

    @Test
    void failIfResourceNameMissing(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(m("node {", "  updateLock(setLabels: 'label1')", "}"), true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        j.assertBuildStatus(Result.FAILURE, b);
        j.assertLogContains("resource name must be specified", b);
    }

    @Test
    void failIfDeleteAndCreateBothSet(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m("node {", "  updateLock(resource: 'res1', createResource: true, deleteResource: true)", "}"), true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        j.assertBuildStatus(Result.FAILURE, b);
        j.assertLogContains("both deleteResource and createResource", b);
    }

    @Test
    void failIfSetLabelsWithAddLabels(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("resource1");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m("node {", "  updateLock(resource: 'resource1', setLabels: 'label1', addLabels: 'label2')", "}"),
                true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b);
        j.assertBuildStatus(Result.FAILURE, b);
        j.assertLogContains("setLabels", b);
    }
}
