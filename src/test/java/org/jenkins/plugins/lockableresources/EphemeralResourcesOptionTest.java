package org.jenkins.plugins.lockableresources;

import static org.junit.Assert.*;

import hudson.EnvVars;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import hudson.model.Result;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesQueueTaskDispatcher;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class EphemeralResourcesOptionTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private LockableResourcesManager manager;

    @Before
    public void setUp() {
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
        manager = LockableResourcesManager.get();
    }

    // BecauseResourcesQueueFailed message tests
    @Test
    public void testGetShortDescription_resourcesNull() {
        Exception cause = new Exception("Test error message");
        LockableResourcesQueueTaskDispatcher.BecauseResourcesQueueFailed blockage =
                new LockableResourcesQueueTaskDispatcher.BecauseResourcesQueueFailed(null, cause);
        assertEquals("Test error message", blockage.getShortDescription());
    }

    @Test
    public void testGetShortDescription_withResources() {
        LockableResourcesStruct resources = new LockableResourcesStruct(Collections.emptyList());
        resources.required = Arrays.asList(new LockableResource("res1"));
        resources.label = "";
        Exception cause = new Exception("Some error");
        LockableResourcesQueueTaskDispatcher.BecauseResourcesQueueFailed blockage =
                new LockableResourcesQueueTaskDispatcher.BecauseResourcesQueueFailed(resources, cause);
        String expected =
                "Execution failed while acquiring the resource " + resources.required.toString() + ". Some error";
        assertEquals(expected, blockage.getShortDescription());
    }

    @Test
    public void testGetShortDescription_ephemeralDisabled() {
        LockableResourcesStruct resources = new LockableResourcesStruct(Collections.emptyList());
        resources.required = Arrays.asList(new LockableResource("res2"));
        resources.label = "label1";
        Exception cause = new Exception("Error: ephemeral resource creation is disabled");
        LockableResourcesQueueTaskDispatcher.BecauseResourcesQueueFailed blockage =
                new LockableResourcesQueueTaskDispatcher.BecauseResourcesQueueFailed(resources, cause);
        String expected = "Could not run due to ephemeral resource creation being disabled: " + cause.getMessage();
        assertEquals(expected, blockage.getShortDescription());
    }

    @Test
    public void testGetShortDescription_withNullCauseMessage() {
        LockableResourcesStruct resources = new LockableResourcesStruct(Collections.emptyList());
        resources.required = Arrays.asList(new LockableResource("res3"));
        resources.label = "";
        Exception cause = new Exception((String) null);
        LockableResourcesQueueTaskDispatcher.BecauseResourcesQueueFailed blockage =
                new LockableResourcesQueueTaskDispatcher.BecauseResourcesQueueFailed(resources, cause);
        String expected =
                "Execution failed while acquiring the resource " + resources.required.toString() + ". " + null;
        assertEquals(expected, blockage.getShortDescription());
    }

    // Resource validation tests
    @Test(expected = IllegalStateException.class)
    public void testMissingResourceThrows() throws Exception {
        manager.setAllowEphemeralResources(false);
        manager.getResources().clear();
        manager.setDeclaredResources(Collections.emptyList());

        DummyRequiredResourcesProperty prop =
                new DummyRequiredResourcesProperty(Collections.singletonList("nonExistingResource"), "", "1");
        new LockableResourcesStruct(prop, new EnvVars());
    }

    @Test(expected = IllegalStateException.class)
    public void testInvalidLabelThrows() throws Exception {
        manager.setAllowEphemeralResources(false);
        manager.getResources().clear();
        manager.setDeclaredResources(Collections.emptyList());

        DummyRequiredResourcesProperty prop =
                new DummyRequiredResourcesProperty(Collections.emptyList(), "badLabel", "1");
        new LockableResourcesStruct(prop, new EnvVars());
    }

    @Test
    public void testValidLabelWithEphemeralDisabled() throws Exception {
        manager.setAllowEphemeralResources(false);
        manager.getResources().clear();
        manager.setDeclaredResources(Collections.emptyList());

        LockableResource resource = new LockableResource("resource1");
        resource.setLabels("goodLabel");
        manager.addResource(resource, false);

        DummyRequiredResourcesProperty prop =
                new DummyRequiredResourcesProperty(Collections.emptyList(), "goodLabel", "1");
        EnvVars env = new EnvVars();

        LockableResourcesStruct struct = new LockableResourcesStruct(prop, env);
        assertEquals("goodLabel", struct.label);
    }

    @Test
    public void testInvalidLabelButEphemeralAllowed() throws Exception {
        manager.setAllowEphemeralResources(true);
        manager.getResources().clear();
        manager.setDeclaredResources(Collections.emptyList());

        DummyRequiredResourcesProperty prop =
                new DummyRequiredResourcesProperty(Collections.emptyList(), "badLabel", "1");
        EnvVars env = new EnvVars();

        LockableResourcesStruct struct = new LockableResourcesStruct(prop, env);
        assertEquals("badLabel", struct.label);
    }

    @Test(expected = IllegalStateException.class)
    public void testMissingResourceStreamBranch() throws Exception {
        manager.setAllowEphemeralResources(false);
        manager.getResources().clear();
        manager.setDeclaredResources(Collections.emptyList());

        LockableResource existing = new LockableResource("existingResource");
        manager.addResource(existing, false);

        DummyRequiredResourcesProperty prop =
                new DummyRequiredResourcesProperty(Arrays.asList("existingResource", "missingResource"), "", "1");
        EnvVars env = new EnvVars();

        try {
            new LockableResourcesStruct(prop, env);
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("missingResource"));
            throw e;
        }
    }

    // Ephemeral resource management tests
    @Test
    public void testCreateResourceEphemeralDisabled() {
        manager.setAllowEphemeralResources(false);
        boolean created = manager.createResource("unexistentRes");
        assertFalse("createResource(...) should return false if ephemeral is OFF", created);
        assertNull("The resource should not be created", manager.fromName("unexistentRes"));
    }

    @Test
    public void testMissingResourceEphemeralDisabled_causesQueueFail() throws Exception {
        manager.setAllowEphemeralResources(false);

        FreeStyleProject p = j.createFreeStyleProject("failJob");
        p.addProperty(new RequiredResourcesProperty("resourceThatDoesNotExist", "", "1", null, null));
        p.scheduleBuild2(0);

        Queue.Item queueItem = waitForOneQueueItem(5);
        assertNotNull("Job should be in queue (blocked)", queueItem);
        assertNotNull("Cause of blockage should exist", queueItem.getCauseOfBlockage());
        String msg = queueItem.getCauseOfBlockage().getShortDescription();
        assertFalse("Blockage message should not be empty", msg.isEmpty());
    }

    @Test
    public void testInvalidLabelEphemeralDisabled() throws Exception {
        manager.setAllowEphemeralResources(false);

        FreeStyleProject p = j.createFreeStyleProject("invalidLabelJob");
        p.addProperty(new RequiredResourcesProperty("", "someInvalidLabel", "1", null, null));
        p.scheduleBuild2(0);

        Queue.Item queueItem = waitForOneQueueItem(5);
        assertNotNull("Job should be in queue (invalid label => causeOfBlockage)", queueItem);
        assertNotNull("Cause of blockage should exist", queueItem.getCauseOfBlockage());
    }

    @Test
    public void testEphemeralResourcesEnabled() throws Exception {
        manager.setAllowEphemeralResources(true);

        FreeStyleProject p = j.createFreeStyleProject("autoJob");
        p.addProperty(new RequiredResourcesProperty("autoRes", "", "1", null, null));

        FreeStyleBuild b = p.scheduleBuild2(0).get(30, TimeUnit.SECONDS);
        j.assertBuildStatus(Result.SUCCESS, b);
        assertNull("Ephemeral resource should not be stored in manager", manager.fromName("autoRes"));
    }

    @Test
    public void testPersistenceOfEphemeralFlag() throws Exception {
        manager.setAllowEphemeralResources(false);
        j.jenkins.reload();
        LockableResourcesManager newManager = LockableResourcesManager.get();
        assertFalse("Ephemeral flag (false) should persist after reload", newManager.isAllowEphemeralResources());
    }

    // Edge cases tests
    @Test
    public void testInvalidNumberEphemeralDisabled() throws Exception {
        manager.setAllowEphemeralResources(false);

        FreeStyleProject p = j.createFreeStyleProject("invalidNumberJob");
        p.addProperty(new RequiredResourcesProperty("res1,res2", "", "NaN", null, null));
        p.scheduleBuild2(0);

        Queue.Item queueItem = waitForOneQueueItem(5);
        assertNotNull("Job should be in queue (invalid number => causeOfBlockage)", queueItem);
        assertNotNull(queueItem.getCauseOfBlockage());
    }

    /**
     * Waits for a job to appear in the queue within a given timeout.
     *
     * @param maxWaitSeconds Maximum wait time in seconds.
     * @return The first queued item, or null if none appeared.
     */
    private Queue.Item waitForOneQueueItem(int maxWaitSeconds) throws InterruptedException {
        Queue.Item result = null;
        for (int i = 0; i < maxWaitSeconds; i++) {
            Queue.Item[] items = j.jenkins.getQueue().getItems();
            if (items.length > 0) {
                result = items[0];
                break;
            }
            Thread.sleep(1000);
        }
        return result;
    }

    private static class DummyRequiredResourcesProperty extends RequiredResourcesProperty {
        private final List<String> resourcesList;
        private final String labelName;

        public DummyRequiredResourcesProperty(List<String> resources, String labelName, String resourceNumber)
                throws hudson.model.Descriptor.FormException {
            super(String.join(",", resources), labelName, resourceNumber, "", null);
            this.resourcesList = resources;
            this.labelName = labelName;
        }

        @Override
        public String[] getResources() {
            return resourcesList.toArray(new String[0]);
        }

        @Override
        public SecureGroovyScript getResourceMatchScript() {
            return null;
        }

        @Override
        public String getResourceNamesVar() {
            return "";
        }

        @Override
        public String getLabelName() {
            return labelName;
        }
    }
}
