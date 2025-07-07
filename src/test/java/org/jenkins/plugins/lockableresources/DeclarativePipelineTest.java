package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertNull;

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

@WithJenkins
class DeclarativePipelineTest {

    // ---------------------------------------------------------------------------
    @BeforeEach
    void setUp() {
        // to speed up the test
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    }

    @Test
    void lockByIdInOptionsSection(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m(
                        "pipeline {",
                        " agent none",
                        " options {",
                        "  lock resource: 'resource1'",
                        " }",
                        " stages {",
                        "  stage('test') {",
                        "   steps {",
                        "    echo 'foo'",
                        "   }",
                        "  }",
                        " }",
                        "}"),
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b1);
        j.assertBuildStatus(Result.SUCCESS, b1);
        j.assertLogContains("Resource [resource1] did not exist. Created.", b1);
        assertNull(LockableResourcesManager.get().fromName("resource1"));
    }

    @Test
    void lockByLabelInOptionsSection(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m(
                        "pipeline {",
                        " agent none",
                        " options {",
                        "  lock label: 'label1', resource : null",
                        " }",
                        " stages {",
                        "  stage('test') {",
                        "   steps {",
                        "    echo 'foo'",
                        "   }",
                        "  }",
                        " }",
                        "}"),
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b1);
        j.assertBuildStatus(Result.SUCCESS, b1);
        j.assertLogContains("Lock acquired on [Label: label1]", b1);
    }

    @Test
    void stepScriptLockByLabel(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m(
                        "pipeline {",
                        " agent none",
                        " stages {",
                        "  stage('test') {",
                        "   steps {",
                        "    script {",
                        "       lock(label: 'label1', resource : null, variable: 'LABEL_LOCKED', quantity: 1) {",
                        "         echo \"Lock acquired: ${LABEL_LOCKED}\"",
                        "       }",
                        "     }",
                        "   }",
                        "  }",
                        " }",
                        "}"),
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b1);
        j.assertBuildStatus(Result.SUCCESS, b1);
        j.assertLogContains("Lock acquired: resource1", b1);
    }

    @Test
    void stepLockByLabel(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label1");
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m(
                        "pipeline {",
                        " agent none",
                        " stages {",
                        "  stage('test') {",
                        "   steps {",
                        "     lock(label: 'label1', resource : null, variable: 'LABEL_LOCKED', quantity: 1) {",
                        "       echo \"Lock acquired: ${LABEL_LOCKED}\"",
                        "     }",
                        "   }",
                        "  }",
                        " }",
                        "}"),
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b1);
        j.assertBuildStatus(Result.SUCCESS, b1);
        j.assertLogContains("Lock acquired: resource1", b1);
    }

    @Test
    void missingLabel(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                m(
                        "pipeline {",
                        " agent none",
                        " stages {",
                        "  stage('test') {",
                        "   steps {",
                        "     lock() {",
                        "       echo \"This will still be executed\"",
                        "     }",
                        "   }",
                        "  }",
                        " }",
                        "}"),
                true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(b1);
        j.assertBuildStatus(Result.FAILURE, b1);
        j.assertLogContains("Missing required parameter: \"resource\"", b1);
    }

    private static String m(String... lines) {
        return Joiner.on('\n').join(lines);
    }
}
