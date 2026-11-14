package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

/** lock(shareId: ...) state across a Jenkins restart, and loading state written by older versions. */
class LockStepShareIdRestartTest extends LockStepTestBase {

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    private static LockableResource res(String name) {
        return LockableResourcesManager.get().fromName(name);
    }

    private static List<String> holders(String name) {
        LockableResource r = res(name);
        assertNotNull(r, name);
        return r.getShareHolderBuilds();
    }

    private static WorkflowJob job(JenkinsRule j, String name, String script) throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, name);
        p.setDefinition(new CpsFlowDefinition(script, true));
        return p;
    }

    /** Waits until build #1 of {@code p} has started. */
    private static WorkflowRun firstBuild(WorkflowJob p) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        WorkflowRun b;
        while ((b = p.getBuildByNumber(1)) == null) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("no build of " + p.getFullName() + " was started");
            }
            Thread.sleep(100);
        }
        return b;
    }

    /**
     * The share of two running builds survives a restart. Entries of a build that is no longer running
     * (left behind as after a crash) are pruned at startup by {@link FreeDeadJobs}: a resource still held
     * by a running build stays locked, one held only by the dead build is freed.
     */
    @Test
    void shareSurvivesRestartAndFreeDeadJobsPrunesDeadHolders() throws Throwable {
        String[] ids = new String[3];
        sessions.then(j -> {
            LockableResourcesManager lrm = LockableResourcesManager.get();
            lrm.createResourceWithLabel("r1", "share-test");
            lrm.createResourceWithLabel("r2", "share-test");
            WorkflowJob done = job(j, "done", "echo 'done'");
            WorkflowRun d = j.buildAndAssertSuccess(done);
            job(j, "child", "lock(resource: 'r1', shareId: 'x') { semaphore 'child' }");
            WorkflowJob parent = job(j, "parent", """
                    lock(resource: 'r1', shareId: 'x') {
                      build job: 'child', wait: false
                      semaphore 'parent'
                    }
                    """);

            WorkflowRun p = parent.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("parent/1", p);
            WorkflowRun c = firstBuild(j.jenkins.getItemByFullName("child", WorkflowJob.class));
            SemaphoreStep.waitForStart("child/1", c);
            assertEquals(Arrays.asList(p.getExternalizableId(), c.getExternalizableId()), holders("r1"));

            // Leave entries of the completed build behind, as a crash before its block ended would:
            // a third holder of r1, and the only holder of r2.
            synchronized (LockableResourcesManager.syncResources) {
                res("r1").addShareHolder(LockableResource.shareHolderEntry(d.getExternalizableId(), "3"));
                res("r2").startShare(d, "x", LockableResource.shareHolderEntry(d.getExternalizableId(), "3"));
            }
            lrm.save();
            assertEquals(
                    Arrays.asList(p.getExternalizableId(), c.getExternalizableId(), d.getExternalizableId()),
                    holders("r1"));
            assertTrue(res("r2").isLocked());
            ids[0] = p.getExternalizableId();
            ids[1] = c.getExternalizableId();
            ids[2] = d.getExternalizableId();
        });

        sessions.then(j -> {
            WorkflowRun p =
                    j.jenkins.getItemByFullName("parent", WorkflowJob.class).getBuildByNumber(1);
            WorkflowRun c =
                    j.jenkins.getItemByFullName("child", WorkflowJob.class).getBuildByNumber(1);
            assertEquals(ids[0], p.getExternalizableId());
            assertEquals(ids[1], c.getExternalizableId());

            // the share of the running builds survived; the dead build's entries are gone
            assertTrue(res("r1").isLocked());
            assertEquals("x", res("r1").getShareId());
            assertEquals(Arrays.asList(ids[0], ids[1]), holders("r1"));
            assertEquals(p, res("r1").getBuild());
            assertFalse(res("r1").isShareHolderBuild(ids[2]));

            // r2 had no holder left: freed
            assertFalse(res("r2").isLocked());
            assertNull(res("r2").getShareId());
            assertTrue(holders("r2").isEmpty());

            // the resumed blocks still release correctly: the parent leaves, the child keeps r1
            SemaphoreStep.success("parent/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(p));
            assertTrue(res("r1").isLocked());
            assertEquals(Collections.singletonList(ids[1]), holders("r1"));
            assertEquals(c, res("r1").getBuild());

            SemaphoreStep.success("child/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(c));
            assertFalse(res("r1").isLocked());
            assertNull(res("r1").getShareId());
            assertTrue(holders("r1").isEmpty());
        });
    }

    /** XML written before shareId existed has neither field: it loads as an unshared resource. */
    @Test
    void oldXmlWithoutShareFieldsLoads() throws Throwable {
        sessions.then(j -> {
            File config = new File(j.jenkins.getRootDir(), LockableResourcesManager.class.getName() + ".xml");
            Files.writeString(config.toPath(), """
                    <?xml version='1.1' encoding='UTF-8'?>
                    <org.jenkins.plugins.lockableresources.LockableResourcesManager plugin="lockable-resources@1349.v0d4b_b_b_13f5d5">
                      <resources>
                        <org.jenkins.plugins.lockableresources.LockableResource>
                          <name>old1</name>
                          <description>written by an older version</description>
                          <labelsAsList>
                            <string>old-label</string>
                          </labelsAsList>
                          <note></note>
                          <lockReason></lockReason>
                          <stolen>false</stolen>
                          <ephemeral>false</ephemeral>
                          <queueItemId>0</queueItemId>
                          <queuingStarted>0</queuingStarted>
                        </org.jenkins.plugins.lockableresources.LockableResource>
                        <org.jenkins.plugins.lockableresources.LockableResource>
                          <name>old2</name>
                          <description></description>
                          <labelsAsList/>
                          <reservedBy>someone</reservedBy>
                          <note></note>
                          <stolen>false</stolen>
                          <ephemeral>false</ephemeral>
                          <queueItemId>0</queueItemId>
                          <queuingStarted>0</queuingStarted>
                        </org.jenkins.plugins.lockableresources.LockableResource>
                      </resources>
                    </org.jenkins.plugins.lockableresources.LockableResourcesManager>
                    """, StandardCharsets.UTF_8);
        });

        sessions.then(j -> {
            LockableResource old1 = res("old1");
            assertNotNull(old1);
            assertEquals("written by an older version", old1.getDescription());
            assertEquals(Collections.singletonList("old-label"), old1.getLabelsAsList());
            assertNull(old1.getShareId());
            assertTrue(old1.getShareHolders().isEmpty());
            assertFalse(old1.hasShareHolders());
            assertTrue(old1.getShareHolderBuilds().isEmpty());

            LockableResource old2 = res("old2");
            assertNotNull(old2);
            assertEquals("someone", old2.getReservedBy());
            assertNull(old2.getShareId());
            assertTrue(old2.getShareHolders().isEmpty());

            // the loaded holder set is usable: two blocks share the old resource
            WorkflowJob p = job(j, "p", """
                    parallel a: {
                      lock(resource: 'old1', shareId: 'x') { semaphore 'a' }
                    }, b: {
                      lock(resource: 'old1', shareId: 'x') { semaphore 'b' }
                    }
                    """);
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("a/1", b);
            SemaphoreStep.waitForStart("b/1", b);
            assertEquals("x", res("old1").getShareId());
            assertEquals(2, res("old1").getShareHolders().size());
            SemaphoreStep.success("a/1", null);
            SemaphoreStep.success("b/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(b));
            assertFalse(res("old1").isLocked());
            assertTrue(res("old1").getShareHolders().isEmpty());
        });
    }
}
