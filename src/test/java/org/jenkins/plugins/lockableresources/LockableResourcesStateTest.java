package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jenkins.plugins.lockableresources.queue.QueuedContextStruct;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

/** Queue of lock steps lives in {@link LockableResourcesState}, separate from the configuration file. */
class LockableResourcesStateTest extends LockStepTestBase {

    private static final String MANAGER_XML = LockableResourcesManager.class.getName() + ".xml";
    private static final String STATE_XML = LockableResourcesState.class.getName() + ".xml";
    private static final String MANAGER_ROOT_END = "</" + LockableResourcesManager.class.getName() + ">";
    private static final Pattern QUEUE_BLOCK = Pattern.compile("(?s)\\s*<queuedContexts>.*</queuedContexts>");
    private static final String ENTRY_TAG = "<" + QueuedContextStruct.class.getName() + ">";

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @BeforeEach
    void enableSave() {
        System.clearProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE);
    }

    @Test
    void legacyQueueInManagerXmlIsMigrated() throws Throwable {
        queueOneBehindHolder("migrate");

        // Rewrite JENKINS_HOME into the old layout: queue inside LockableResourcesManager.xml, no state file.
        moveQueueIntoManagerXml(true);

        sessions.then(j -> {
            assertEquals(
                    1, LockableResourcesManager.get().getCurrentQueuedContext().size());
            Path home = j.jenkins.getRootDir().toPath();
            assertTrue(Files.exists(home.resolve(STATE_XML)), "state file written by the migration");
            assertEquals(1, countEntries(read(home.resolve(STATE_XML))));
            waitUntil(() -> countEntries(read(home.resolve(MANAGER_XML))) == 0);

            WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b1 = p.getBuildByNumber(1);
            WorkflowRun b2 = p.getBuildByNumber(2);
            SemaphoreStep.success("migrate/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(b1));
            SemaphoreStep.success("migrate/2", null);
            j.waitForMessage("Lock acquired on [Resource: resource1]", b2);
            j.assertBuildStatusSuccess(j.waitForCompletion(b2));
            assertTrue(LockableResourcesManager.get().getCurrentQueuedContext().isEmpty());
        });
    }

    @Test
    void existingStateFileWinsOverLegacyQueue() throws Throwable {
        queueOneBehindHolder("wins");

        // Leftover after a crash in the middle of a migration: the entry is in both files.
        moveQueueIntoManagerXml(false);

        sessions.then(j -> {
            assertEquals(
                    1,
                    LockableResourcesManager.get().getCurrentQueuedContext().size(),
                    "legacy copy dropped, not added twice");
            Path home = j.jenkins.getRootDir().toPath();
            waitUntil(() -> countEntries(read(home.resolve(MANAGER_XML))) == 0);

            WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
            SemaphoreStep.success("wins/1", null);
            SemaphoreStep.success("wins/2", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(p.getBuildByNumber(1)));
            j.assertBuildStatusSuccess(j.waitForCompletion(p.getBuildByNumber(2)));
        });
    }

    @Test
    void queueChangesDoNotSaveConfiguration() throws Throwable {
        sessions.then(j -> {
            LockableResourcesManager lrm = LockableResourcesManager.get();
            lrm.createResource("resource1");
            lrm.reserve(Collections.singletonList(lrm.fromName("resource1")), "test");
            WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("lock('resource1') { echo 'inside' }", true));

            Path managerXml = j.jenkins.getRootDir().toPath().resolve(MANAGER_XML);
            Path stateXml = j.jenkins.getRootDir().toPath().resolve(STATE_XML);
            waitUntil(() -> read(managerXml).contains("<reservedBy>test</reservedBy>"));
            Thread.sleep(2_500); // let any pending coalesced save run
            byte[] before = Files.readAllBytes(managerXml);
            long modifiedBefore = Files.getLastModifiedTime(managerXml).toMillis();

            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
            j.waitForMessage("The resource [resource1] is reserved by test", b1);
            WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
            j.waitForMessage("The resource [resource1] is reserved by test", b2);
            waitUntil(() -> countEntries(read(stateXml)) == 2);

            // Move b2 to the front: the new order is persisted (it was not saved before).
            List<QueuedContextStruct> queue = lrm.getCurrentQueuedContext();
            String b2Id = queue.get(1).getId();
            lrm.changeQueueOrder(b2Id, 0);
            waitUntil(() -> read(stateXml).indexOf("<id>" + b2Id + "</id>")
                    < read(stateXml).lastIndexOf("<id>"));

            // Stopping b1 unqueues it.
            b1.doKill();
            j.waitForCompletion(b1);
            waitUntil(() -> countEntries(read(stateXml)) == 1);

            Thread.sleep(2_500);
            assertArrayEquals(before, Files.readAllBytes(managerXml), "configuration file content unchanged");
            assertEquals(
                    modifiedBefore,
                    Files.getLastModifiedTime(managerXml).toMillis(),
                    "configuration file not rewritten by queue changes");
            assertEquals(0, countEntries(read(managerXml)));

            lrm.unreserve(Collections.singletonList(lrm.fromName("resource1")));
            j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        });
    }

    @Test
    void configurationIsWrittenBeforeQueueState() throws Throwable {
        sessions.then(j -> {
            LockableResourcesManager lrm = LockableResourcesManager.get();
            lrm.createResource("resource1");
            lrm.reserve(Collections.singletonList(lrm.fromName("resource1")), "test");
            WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("lock('resource1') { semaphore 'held' }", true));
            Path managerXml = j.jenkins.getRootDir().toPath().resolve(MANAGER_XML);
            Path stateXml = j.jenkins.getRootDir().toPath().resolve(STATE_XML);

            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
            j.waitForMessage("The resource [resource1] is reserved by test", b1);
            waitUntil(() -> countEntries(read(stateXml)) == 1);

            // The queued build gets the resource. Once its entry is gone from the state file, the lock
            // is already in the configuration file: a hard kill now loses neither.
            lrm.unreserve(Collections.singletonList(lrm.fromName("resource1")));
            waitUntil(() -> countEntries(read(stateXml)) == 0);
            assertTrue(
                    read(managerXml)
                            .contains(
                                    "<buildExternalizableId>" + b1.getExternalizableId() + "</buildExternalizableId>"),
                    "lock written before the queue entry was removed");

            SemaphoreStep.success("held/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        });
    }

    @Test
    void queueOrderSurvivesRestart() throws Throwable {
        sessions.then(j -> {
            LockableResourcesManager.get().createResource("resource1");
            WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("lock('resource1') { semaphore 'order' }", true));
            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("order/1", b1);
            WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
            j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b2);
            WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
            j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b3);

            LockableResourcesManager lrm = LockableResourcesManager.get();
            assertEquals(2, lrm.getCurrentQueuedContext().size());
            lrm.changeQueueOrder(lrm.getCurrentQueuedContext().get(1).getId(), 0);
            assertEquals(b3, lrm.getCurrentQueuedContext().get(0).getBuild());
            // No wait: the session ends right away, so the shutdown flush has to write the queue.
        });

        sessions.then(j -> {
            LockableResourcesManager lrm = LockableResourcesManager.get();
            WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b1 = p.getBuildByNumber(1);
            WorkflowRun b2 = p.getBuildByNumber(2);
            WorkflowRun b3 = p.getBuildByNumber(3);
            assertEquals(2, lrm.getCurrentQueuedContext().size());
            assertEquals(b3, lrm.getCurrentQueuedContext().get(0).getBuild());

            SemaphoreStep.success("order/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(b1));
            SemaphoreStep.waitForStart("order/2", b3);
            isPaused(b2, 1, 1);
            SemaphoreStep.success("order/2", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(b3));
            SemaphoreStep.waitForStart("order/3", b2);
            SemaphoreStep.success("order/3", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(b2));
        });
    }

    /** b1 holds resource1 inside {@code semaphore id}, b2 waits in the queue; the state file has the entry. */
    private void queueOneBehindHolder(String id) throws Throwable {
        sessions.then(j -> {
            LockableResourcesManager.get().createResource("resource1");
            WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("lock('resource1') { semaphore '" + id + "' }", true));
            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart(id + "/1", b1);
            WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
            j.waitForMessage("[resource1] is locked by build " + b1.getFullDisplayName(), b2);
            Path stateXml = j.jenkins.getRootDir().toPath().resolve(STATE_XML);
            waitUntil(() -> countEntries(read(stateXml)) == 1);
        });
    }

    /** Copies the queue block from the state file into the manager file, as an old plugin version wrote it. */
    private void moveQueueIntoManagerXml(boolean deleteStateFile) throws Exception {
        File home = sessions.getHome();
        Path stateXml = home.toPath().resolve(STATE_XML);
        Path managerXml = home.toPath().resolve(MANAGER_XML);
        Matcher m = QUEUE_BLOCK.matcher(read(stateXml));
        assertTrue(m.find(), "queue block in state file");
        String manager = read(managerXml);
        assertEquals(0, countEntries(manager));
        int end = manager.lastIndexOf(MANAGER_ROOT_END);
        assertTrue(end > 0);
        Files.writeString(
                managerXml,
                manager.substring(0, end) + m.group() + "\n" + manager.substring(end),
                StandardCharsets.UTF_8);
        if (deleteStateFile) {
            Files.delete(stateXml);
        }
    }

    private static int countEntries(String xml) {
        int count = 0;
        for (int i = xml.indexOf(ENTRY_TAG); i >= 0; i = xml.indexOf(ENTRY_TAG, i + 1)) {
            count++;
        }
        return count;
    }

    private static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within 30s");
            }
            Thread.sleep(100);
        }
    }
}
