package org.jenkins.plugins.lockableresources.actions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Run;
import hudson.tasks.LogRotator;
import java.lang.reflect.Field;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import jenkins.model.BuildDiscarderProperty;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class UnlockOnBuildDeleteTest {

    @Test
    @Issue("707")
    void deleteBuildWithNullResourcesInUse(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("resource1", "label1");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("""
                lock('resource1') {
                    echo 'x'
                }""", true));
        WorkflowRun b1 = j.buildAndAssertSuccess(p);

        // Simulate an old build.xml without the resourcesInUse field.
        nullResourcesInUse(b1);

        assertDoesNotThrow(() -> lrm.unlockBuild(b1));

        try (LogRecorder logging = new LogRecorder().record("", Level.WARNING).capture(100)) {
            b1.delete();
            assertNoNullPointerExceptionLogged(logging);
        }

        assertNull(p.getBuildByNumber(1));
        assertResourceFree(lrm, "resource1");
    }

    @Test
    @Issue("707")
    void logRotationDeletesBuildWithNullResourcesInUse(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResourceWithLabel("resource1", "label1");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.addProperty(new BuildDiscarderProperty(new LogRotator(-1, 1, -1, -1)));
        p.setDefinition(new CpsFlowDefinition("""
                lock('resource1') {
                    echo 'x'
                }""", true));

        WorkflowRun b1 = j.buildAndAssertSuccess(p);
        nullResourcesInUse(b1);

        try (LogRecorder logging = new LogRecorder().record("", Level.WARNING).capture(100)) {
            j.buildAndAssertSuccess(p);
            j.waitUntilNoActivity();
            assertNoNullPointerExceptionLogged(logging);
        }

        assertNull(p.getBuildByNumber(1));
        assertNotNull(p.getBuildByNumber(2));
        assertResourceFree(lrm, "resource1");
    }

    private static void nullResourcesInUse(Run<?, ?> build) throws Exception {
        LockedResourcesBuildAction action = build.getAction(LockedResourcesBuildAction.class);
        assertNotNull(action);

        Field resourcesInUseField = LockedResourcesBuildAction.class.getDeclaredField("resourcesInUse");
        resourcesInUseField.setAccessible(true);
        resourcesInUseField.set(action, null);
    }

    private static void assertNoNullPointerExceptionLogged(LogRecorder logging) {
        for (LogRecord logRecord : logging.getRecords()) {
            for (Throwable t = logRecord.getThrown(); t != null; t = t.getCause()) {
                assertFalse(
                        t instanceof NullPointerException, "NullPointerException logged: " + logRecord.getMessage());
            }
        }
    }

    private static void assertResourceFree(LockableResourcesManager lrm, String name) {
        LockableResource resource = lrm.fromName(name);
        assertNotNull(resource);
        assertTrue(resource.isFree());
    }
}
