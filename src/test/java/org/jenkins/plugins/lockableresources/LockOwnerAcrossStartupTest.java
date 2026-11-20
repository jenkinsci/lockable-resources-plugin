package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Configuration as Code applies at startup before jobs are loaded, so the build holding a lock
 * can't be resolved yet. The recorded owner must survive that, and be released once jobs are
 * loaded if the build is really gone.
 */
@WithJenkins
class LockOwnerAcrossStartupTest {

    private static final String UNLOADED_BUILD = "not-loaded-yet#1";

    private static void recordOwner(LockableResource resource, String externalizableId) throws Exception {
        Field field = LockableResource.class.getDeclaredField("buildExternalizableId");
        field.setAccessible(true);
        field.set(resource, externalizableId);
    }

    @Test
    void declaredResourceKeepsRecordedOwnerWhenCascApplies(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("declared");
        recordOwner(lrm.fromName("declared"), UNLOADED_BUILD);

        lrm.setDeclaredResources(List.of(new LockableResource("declared")));

        LockableResource after = lrm.fromName("declared");
        assertNotNull(after);
        assertTrue(after.hasRecordedBuild(), "owner must survive the CasC merge");
        assertFalse(after.isEphemeral());
    }

    @Test
    void undeclaredResourceKeepsRecordedOwnerWhenCascApplies(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("undeclared");
        recordOwner(lrm.fromName("undeclared"), UNLOADED_BUILD);

        lrm.setDeclaredResources(List.of());

        LockableResource after = lrm.fromName("undeclared");
        assertNotNull(after, "a held resource must not be dropped by CasC");
        assertTrue(after.hasRecordedBuild());
        assertTrue(after.isEphemeral());
    }

    @Test
    void freeResourceStillFollowsConfiguration(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("free");

        lrm.setDeclaredResources(List.of());

        assertNull(lrm.fromName("free"));
    }

    @Test
    void ownerThatNeverLoadsIsReleasedAfterJobsLoad(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("declared");
        lrm.createResource("undeclared");
        recordOwner(lrm.fromName("declared"), UNLOADED_BUILD);
        recordOwner(lrm.fromName("undeclared"), UNLOADED_BUILD);
        lrm.setDeclaredResources(List.of(new LockableResource("declared")));

        FreeDeadJobs.freePostMortemResources();

        LockableResource declared = lrm.fromName("declared");
        assertNotNull(declared);
        assertFalse(declared.hasRecordedBuild());
        assertFalse(declared.isLocked());
        assertNull(lrm.fromName("undeclared"), "released ephemeral resource is removed");
    }
}
