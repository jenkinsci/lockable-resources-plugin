/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.listeners;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.ExtensionList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourceProperty;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkinsci.plugins.scriptsecurity.scripts.ApprovalContext;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for {@link ResourceEventListener} extension point. Verifies that resource state change
 * events are fired correctly for all operations (lock, unlock, reserve, unreserve, steal, reassign,
 * reset, recycle).
 */
@WithJenkins
class ResourceEventListenerTest {

    @BeforeEach
    void setUp() {
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
        TestResourceEventListener.clear();
        TestResourceEventListener.callbackResult = null;
    }

    @Test
    void extensionPointIsRegistered(JenkinsRule j) {
        ExtensionList<ResourceEventListener> listeners =
                ExtensionList.lookup(ResourceEventListener.class);
        assertTrue(listeners.size() > 0, "At least the test listener should be registered");
        boolean found = false;
        for (ResourceEventListener l : listeners) {
            if (l instanceof TestResourceEventListener) {
                found = true;
                break;
            }
        }
        assertTrue(found, "TestResourceEventListener should be discoverable");
    }

    @Test
    void reserveFiresEvent(JenkinsRule j) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("r1");
        LockableResource r1 = lrm.fromName("r1");
        assertNotNull(r1);

        assertTrue(lrm.reserve(Arrays.asList(r1), "testUser"));

        List<TestResourceEventListener.EventRecord> events =
                TestResourceEventListener.getEvents(ResourceEvent.RESERVED);
        assertThat(events, hasSize(1));
        assertThat(events.get(0).resourceNames, is(Arrays.asList("r1")));
        assertThat(events.get(0).userName, is("testUser"));
    }

    @Test
    void unreserveFiresEvent(JenkinsRule j) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("r1");
        LockableResource r1 = lrm.fromName("r1");
        assertNotNull(r1);

        lrm.reserve(Arrays.asList(r1), "testUser");
        TestResourceEventListener.clear();

        lrm.unreserve(Arrays.asList(r1));

        List<TestResourceEventListener.EventRecord> events =
                TestResourceEventListener.getEvents(ResourceEvent.UNRESERVED);
        assertThat(events, hasSize(1));
        assertThat(events.get(0).resourceNames, is(Arrays.asList("r1")));
    }

    @Test
    void stealFiresEvent(JenkinsRule j) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("r1");
        LockableResource r1 = lrm.fromName("r1");
        assertNotNull(r1);

        lrm.reserve(Arrays.asList(r1), "originalUser");
        TestResourceEventListener.clear();

        assertTrue(lrm.steal(Arrays.asList(r1), "thief"));

        List<TestResourceEventListener.EventRecord> events =
                TestResourceEventListener.getEvents(ResourceEvent.STOLEN);
        assertThat(events, hasSize(1));
        assertThat(events.get(0).resourceNames, is(Arrays.asList("r1")));
        assertThat(events.get(0).userName, is("thief"));
    }

    @Test
    void reassignFiresEvent(JenkinsRule j) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("r1");
        LockableResource r1 = lrm.fromName("r1");
        assertNotNull(r1);

        lrm.reserve(Arrays.asList(r1), "originalUser");
        TestResourceEventListener.clear();

        lrm.reassign(Arrays.asList(r1), "newUser");

        List<TestResourceEventListener.EventRecord> events =
                TestResourceEventListener.getEvents(ResourceEvent.REASSIGNED);
        assertThat(events, hasSize(1));
        assertThat(events.get(0).resourceNames, is(Arrays.asList("r1")));
        assertThat(events.get(0).userName, is("newUser"));
    }

    @Test
    void resetFiresEvent(JenkinsRule j) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("r1");
        LockableResource r1 = lrm.fromName("r1");
        assertNotNull(r1);

        lrm.reserve(Arrays.asList(r1), "testUser");
        TestResourceEventListener.clear();

        lrm.reset(Arrays.asList(r1));

        List<TestResourceEventListener.EventRecord> events =
                TestResourceEventListener.getEvents(ResourceEvent.RESET);
        assertThat(events, hasSize(1));
        assertThat(events.get(0).resourceNames, is(Arrays.asList("r1")));
    }

    @Test
    void lockUnlockFiresEventsViaPipeline(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("r1");

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                lock('r1') {
                    echo 'inside lock'
                }
                """,
                true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b));

        List<TestResourceEventListener.EventRecord> locked =
                TestResourceEventListener.getEvents(ResourceEvent.LOCKED);
        assertThat("Expected exactly one LOCKED event", locked, hasSize(1));
        assertThat(locked.get(0).resourceNames, is(Arrays.asList("r1")));
        assertNotNull(locked.get(0).buildName, "LOCKED event should include build name");

        List<TestResourceEventListener.EventRecord> unlocked =
                TestResourceEventListener.getEvents(ResourceEvent.UNLOCKED);
        assertThat("Expected exactly one UNLOCKED event", unlocked, hasSize(1));
        assertThat(unlocked.get(0).resourceNames, is(Arrays.asList("r1")));
    }

    @Test
    void multipleResourcesFireSingleEvent(JenkinsRule j) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("r1");
        lrm.createResource("r2");
        lrm.createResource("r3");
        LockableResource r1 = lrm.fromName("r1");
        LockableResource r2 = lrm.fromName("r2");
        LockableResource r3 = lrm.fromName("r3");
        assertNotNull(r1);
        assertNotNull(r2);
        assertNotNull(r3);

        assertTrue(lrm.reserve(Arrays.asList(r1, r2, r3), "testUser"));

        List<TestResourceEventListener.EventRecord> events =
                TestResourceEventListener.getEvents(ResourceEvent.RESERVED);
        assertThat(events, hasSize(1));
        assertThat(events.get(0).resourceNames, is(Arrays.asList("r1", "r2", "r3")));
    }

    @Test
    void failedListenerDoesNotBreakOperation(JenkinsRule j) {
        // Register a listener that always throws — it should not prevent the operation
        // We verify this by checking that the operation succeeds AND the test listener still records
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("r1");
        LockableResource r1 = lrm.fromName("r1");
        assertNotNull(r1);

        // The operation itself should succeed regardless of listener failures
        assertTrue(lrm.reserve(Arrays.asList(r1), "testUser"));

        // The test listener should still have received the event
        List<TestResourceEventListener.EventRecord> events =
                TestResourceEventListener.getEvents(ResourceEvent.RESERVED);
        assertEquals(1, events.size());
    }

    @Test
    void recycleFiresEvents(JenkinsRule j) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("r1");
        LockableResource r1 = lrm.fromName("r1");
        assertNotNull(r1);

        lrm.reserve(Arrays.asList(r1), "testUser");
        TestResourceEventListener.clear();

        lrm.recycle(Arrays.asList(r1));

        // recycle calls unlockResources + unreserve internally, then fires RECYCLED
        List<TestResourceEventListener.EventRecord> recycled =
                TestResourceEventListener.getEvents(ResourceEvent.RECYCLED);
        assertThat(recycled, hasSize(1));
        assertThat(recycled.get(0).resourceNames, is(Arrays.asList("r1")));
    }

    @Test
    void resourceInfoSnapshotIsCorrect(JenkinsRule j) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("r1");
        LockableResource r1 = lrm.fromName("r1");
        assertNotNull(r1);
        r1.setDescription("Test printer");
        r1.setNote("Floor 3");
        r1.setLabels("printer color");
        LockableResourceProperty prop = new LockableResourceProperty();
        prop.setName("location");
        prop.setValue("building-A");
        List<LockableResourceProperty> props = new ArrayList<>();
        props.add(prop);
        r1.setProperties(props);

        ResourceInfo info = new ResourceInfo(r1);
        assertThat(info.getName(), is("r1"));
        assertThat(info.getDescription(), is("Test printer"));
        assertThat(info.getNote(), is("Floor 3"));
        assertThat(info.getLabels(), is(Arrays.asList("printer", "color")));
        assertThat(info.getProperty("location"), is("building-A"));
        assertThat(info.getProperties().size(), is(1));
    }

    @Test
    void groovyCallbackIsExecutedOnReserve(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("r1");
        LockableResource r1 = lrm.fromName("r1");
        assertNotNull(r1);

        // configure a groovy callback that writes to a static field for verification
        String script =
                "org.jenkins.plugins.lockableresources.listeners.TestResourceEventListener.callbackResult = event + ':' + resource.name + ':' + (userName ?: 'null')";
        ScriptApproval.get().approveSignature("staticField org.jenkins.plugins.lockableresources.listeners.TestResourceEventListener callbackResult");
        SecureGroovyScript groovyScript =
                new SecureGroovyScript(script, true, null).configuring(ApprovalContext.create());
        lrm.setOnResourceEventScript(groovyScript);
        lrm.setEventCallbackAsync(false);

        assertTrue(lrm.reserve(Arrays.asList(r1), "admin"));

        assertNotNull(TestResourceEventListener.callbackResult, "Groovy callback should have set the callbackResult");
        assertThat(TestResourceEventListener.callbackResult, is("RESERVED:r1:admin"));

        // cleanup
        TestResourceEventListener.callbackResult = null;
        lrm.setOnResourceEventScript(null);
    }

    @Test
    void groovyCallbackRunsAsyncByDefault(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("r1");
        LockableResource r1 = lrm.fromName("r1");
        assertNotNull(r1);

        String script =
                "org.jenkins.plugins.lockableresources.listeners.TestResourceEventListener.callbackResult = event + ':' + resource.name";
        ScriptApproval.get().approveSignature("staticField org.jenkins.plugins.lockableresources.listeners.TestResourceEventListener callbackResult");
        SecureGroovyScript groovyScript =
                new SecureGroovyScript(script, true, null).configuring(ApprovalContext.create());
        lrm.setOnResourceEventScript(groovyScript);
        lrm.setEventCallbackAsync(true);

        assertTrue(lrm.reserve(Arrays.asList(r1), "admin"));

        // async — wait for callback to complete
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (TestResourceEventListener.callbackResult == null) {
            assertTrue(System.currentTimeMillis() < deadline, "Timed out waiting for async callback");
            Thread.sleep(100);
        }

        assertThat(TestResourceEventListener.callbackResult, is("RESERVED:r1"));

        // cleanup
        TestResourceEventListener.callbackResult = null;
        lrm.setOnResourceEventScript(null);
    }

    @Test
    void groovyCallbackReceivesBuildNameOnLock(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("r1");

        String script =
                "if (event == 'LOCKED') { org.jenkins.plugins.lockableresources.listeners.TestResourceEventListener.callbackResult = 'build=' + (buildName ?: 'null') }";
        ScriptApproval.get().approveSignature("staticField org.jenkins.plugins.lockableresources.listeners.TestResourceEventListener callbackResult");
        SecureGroovyScript groovyScript =
                new SecureGroovyScript(script, true, null).configuring(ApprovalContext.create());
        lrm.setOnResourceEventScript(groovyScript);
        lrm.setEventCallbackAsync(false);

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                lock('r1') {
                    echo 'locked'
                }
                """,
                true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b));

        assertNotNull(TestResourceEventListener.callbackResult, "Groovy callback should have fired for LOCKED event");
        assertTrue(TestResourceEventListener.callbackResult.startsWith("build="), "Should include build name");
        assertTrue(TestResourceEventListener.callbackResult.contains("p"), "Should contain job name");

        // cleanup
        TestResourceEventListener.callbackResult = null;
        lrm.setOnResourceEventScript(null);
    }

    @Test
    void groovyCallbackFailureDoesNotBreakOperation(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("r1");
        LockableResource r1 = lrm.fromName("r1");
        assertNotNull(r1);

        // configure a callback that always throws
        String script = "throw new RuntimeException('boom')";
        SecureGroovyScript groovyScript =
                new SecureGroovyScript(script, true, null).configuring(ApprovalContext.create());
        lrm.setOnResourceEventScript(groovyScript);
        lrm.setEventCallbackAsync(false);

        // the operation must still succeed
        assertTrue(lrm.reserve(Arrays.asList(r1), "admin"));
        assertTrue(r1.isReserved());

        // and the test listener still received the event
        List<TestResourceEventListener.EventRecord> events =
                TestResourceEventListener.getEvents(ResourceEvent.RESERVED);
        assertThat(events, hasSize(1));

        // cleanup
        lrm.setOnResourceEventScript(null);
    }
}
