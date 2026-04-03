/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for the allowEphemeralResources configuration option.
 *
 * <p>When enabled (default), locking a non-existent resource creates it automatically.
 * When disabled, jobs block waiting for the resource to be manually created.
 *
 * @see <a href="https://github.com/jenkinsci/lockable-resources-plugin/issues/651">Issue #651</a>
 */
@WithJenkins
class EphemeralResourcesOptionTest extends LockStepTestBase {

    // -------------------------------------------------------------------------
    // Default behavior tests (ephemeral resources enabled)
    // -------------------------------------------------------------------------

    @Test
    @Issue("651")
    void ephemeralResourcesEnabledByDefault(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();

        // Verify default state
        assertTrue(lrm.isAllowEphemeralResources(), "Ephemeral resources should be enabled by default");
    }

    @Test
    @Issue("651")
    void resourceCreatedWhenEphemeralEnabled(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.setAllowEphemeralResources(true);

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                lock('ephemeral-resource-1') {
                    echo 'Resource locked'
                }
                echo 'Finish'""",
                true));

        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogContains("Resource [ephemeral-resource-1] did not exist. Created.", b1);
        j.assertLogContains("Resource locked", b1);

        // Ephemeral resources are deleted after the lock is released
        assertNull(lrm.fromName("ephemeral-resource-1"));
    }

    // -------------------------------------------------------------------------
    // createResource method tests
    // -------------------------------------------------------------------------

    @Test
    @Issue("651")
    void createResourceMethodRespectsEphemeralSetting(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();

        // When enabled, createResource should work
        lrm.setAllowEphemeralResources(true);
        assertTrue(lrm.createResource("test-resource-enabled"));
        assertNotNull(lrm.fromName("test-resource-enabled"));

        // When disabled, createResource should return false
        lrm.setAllowEphemeralResources(false);
        assertFalse(lrm.createResource("test-resource-disabled"));
        assertNull(lrm.fromName("test-resource-disabled"));

        // Clean up
        lrm.setAllowEphemeralResources(true);
    }

    // -------------------------------------------------------------------------
    // Configuration toggle tests
    // -------------------------------------------------------------------------

    @Test
    @Issue("651")
    void toggleEphemeralResourcesSetting(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();

        // Default should be true
        assertTrue(lrm.isAllowEphemeralResources());

        // Toggle to false
        lrm.setAllowEphemeralResources(false);
        assertFalse(lrm.isAllowEphemeralResources());

        // Toggle back to true
        lrm.setAllowEphemeralResources(true);
        assertTrue(lrm.isAllowEphemeralResources());
    }

    @Test
    @Issue("651")
    void createResourceWithLabelNotAffectedByEphemeralSetting(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();

        // Disable ephemeral resources
        lrm.setAllowEphemeralResources(false);

        // createResourceWithLabel should still work (it's for explicit creation)
        assertTrue(lrm.createResourceWithLabel("explicit-resource", "my-label"));
        assertNotNull(lrm.fromName("explicit-resource"));

        // Clean up
        lrm.setAllowEphemeralResources(true);
    }

    // -------------------------------------------------------------------------
    // Label-based locking (not affected by ephemeral setting)
    // -------------------------------------------------------------------------

    @Test
    @Issue("651")
    void labelBasedLockingNotAffectedByEphemeralSetting(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.setAllowEphemeralResources(false);

        // Create a resource with a label
        lrm.createResourceWithLabel("labeled-resource", "my-label");

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                lock(label: 'my-label') {
                    echo 'Label-based lock acquired'
                }
                echo 'Finish'""",
                true));

        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogContains("Label-based lock acquired", b1);

        // Clean up
        lrm.setAllowEphemeralResources(true);
    }

    @Test
    @Issue("651")
    void existingResourcesNotAffectedByEphemeralSetting(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();

        // First create a resource while ephemeral is enabled
        lrm.setAllowEphemeralResources(true);
        lrm.createResourceWithLabel("persistent-resource", "");

        // Now disable ephemeral resources
        lrm.setAllowEphemeralResources(false);

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                lock('persistent-resource') {
                    echo 'Locked existing resource'
                }
                echo 'Finish'""",
                true));

        // Locking an existing resource should work fine
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertLogContains("Locked existing resource", b1);

        // Clean up
        lrm.setAllowEphemeralResources(true);
    }
}
