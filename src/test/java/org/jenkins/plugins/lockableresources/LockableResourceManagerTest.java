package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.model.AutoCompletionCandidates;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.AccessDeniedException3;
import hudson.util.FormValidation;
import java.util.Arrays;
import java.util.Collections;
import java.util.TreeSet;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.core.context.SecurityContextHolder;

@WithJenkins
class LockableResourceManagerTest {

    @Test
    void validationFailure(JenkinsRule j) throws Exception {
        RequiredResourcesProperty.DescriptorImpl d = new RequiredResourcesProperty.DescriptorImpl();
        LockableResourcesManager.get().createResource("resource1");
        LockableResource r = LockableResourcesManager.get().getFirst();
        r.setLabelsFromString("some-label");

        assertEquals(
                "Only resource label, groovy expression, or resource names can be defined, not more than one.",
                d.doCheckResourceNames("resource1", null, true, null).getMessage());
        assertEquals(
                "Only resource label, groovy expression, or resource names can be defined, not more than one.",
                d.doCheckResourceNames("resource1", "some-label", false, null).getMessage());
        assertEquals(
                "Only resource label, groovy expression, or resource names can be defined, not more than one.",
                d.doCheckResourceNames("resource1", "some-label", true, null).getMessage());
        assertEquals(
                "Only resource label, groovy expression, or resource names can be defined, not more than one.",
                d.doCheckLabelName("some-label", "resource1", false, null).getMessage());
        assertEquals(
                "Only resource label, groovy expression, or resource names can be defined, not more than one.",
                d.doCheckLabelName("some-label", null, true, null).getMessage());
        assertEquals(
                "Only resource label, groovy expression, or resource names can be defined, not more than one.",
                d.doCheckLabelName("some-label", "resource1", true, null).getMessage());

        assertEquals(FormValidation.ok(), d.doCheckResourceNames("resource1", null, false, null));
        assertEquals(FormValidation.ok(), d.doCheckLabelName("some-label", null, false, null));

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .to("user")
                .grant(Jenkins.READ, Item.CONFIGURE)
                .everywhere()
                .to("cfg_user")
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("manager"));

        j.buildAndAssertSuccess(j.createFreeStyleProject("aProject"));
        Item item = j.jenkins.getItem("aProject");
        assertNotNull(item);

        User user = User.get("user", true, Collections.emptyMap());
        SecurityContextHolder.getContext().setAuthentication(user.impersonate2());
        // the 'user' has no permission
        assertThrows(AccessDeniedException3.class, () -> d.doCheckResourceNames("resource1", null, false, item));
        assertThrows(AccessDeniedException3.class, () -> d.doCheckLabelName("some-label", null, false, item));

        User cfg_user = User.get("cfg_user", true, Collections.emptyMap());
        SecurityContextHolder.getContext().setAuthentication(cfg_user.impersonate2());
        assertEquals(FormValidation.ok(), d.doCheckResourceNames("resource1", null, false, item));
        assertEquals(FormValidation.ok(), d.doCheckLabelName("some-label", null, false, item));

        User manager = User.get("manager", true, Collections.emptyMap());
        SecurityContextHolder.getContext().setAuthentication(manager.impersonate2());
        assertEquals(FormValidation.ok(), d.doCheckResourceNames("resource1", null, false, item));
        assertEquals(FormValidation.ok(), d.doCheckLabelName("some-label", null, false, item));
    }

    @Test
    void doAutoCompleteLabelName(JenkinsRule j) throws Exception {
        RequiredResourcesProperty.DescriptorImpl d = new RequiredResourcesProperty.DescriptorImpl();
        LockableResourcesManager.get().createResourceWithLabel("resource1", "label-1-A label-2-B");
        LockableResourcesManager.get().createResource("resource2");
        LockableResourcesManager.get().createResourceWithLabel("resource3", "label-1-A");
        LockableResourcesManager.get().createResourceWithLabel("resource4", "Label-1");

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .to("user")
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("manager"));

        j.buildAndAssertSuccess(j.createFreeStyleProject("aProject"));
        Item item = j.jenkins.getItem("aProject");
        assertNotNull(item);

        User user = User.get("user", true, Collections.emptyMap());
        SecurityContextHolder.getContext().setAuthentication(user.impersonate2());
        // the 'user' has no permission
        assertThrows(AccessDeniedException3.class, () -> d.doAutoCompleteLabelName("resource1", item));

        User manager = User.get("manager", true, Collections.emptyMap());
        SecurityContextHolder.getContext().setAuthentication(manager.impersonate2());

        assertContains(d.doAutoCompleteLabelName("lab", item), "label-1-A", "label-2-B");
        assertContains(d.doAutoCompleteLabelName("label-1", item), "label-1-A");
        assertContains(d.doAutoCompleteLabelName("label-2", item), "label-2-B");
        assertContains(d.doAutoCompleteLabelName("Lab", item), "Label-1");
        d.doAutoCompleteLabelName(null, item);
        d.doAutoCompleteLabelName("", item);
        d.doAutoCompleteLabelName("resource1", item);
    }

    @Test
    void doAutoCompleteResourceNames(JenkinsRule j) throws Exception {
        RequiredResourcesProperty.DescriptorImpl d = new RequiredResourcesProperty.DescriptorImpl();
        LockableResourcesManager.get().createResource("resource1");
        LockableResourcesManager.get().createResource("Resource1");
        LockableResourcesManager.get().createResource("resource2");

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .to("user")
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("manager"));

        j.buildAndAssertSuccess(j.createFreeStyleProject("aProject"));
        Item item = j.jenkins.getItem("aProject");
        assertNotNull(item);

        User user = User.get("user", true, Collections.emptyMap());
        SecurityContextHolder.getContext().setAuthentication(user.impersonate2());
        // the 'user' has no permission
        assertThrows(
                AccessDeniedException3.class,
                () -> RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames("resource1", item));

        User manager = User.get("manager", true, Collections.emptyMap());
        SecurityContextHolder.getContext().setAuthentication(manager.impersonate2());

        assertContains(RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames("Res", item), "Resource1");
        assertContains(
                RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames("res", item),
                "resource1",
                "resource2");
        RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames(null, item);
        RequiredResourcesProperty.DescriptorImpl.doAutoCompleteResourceNames("", item);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFreeResourcesAmountSkipsUnloggedCausesScan(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        manager.createResourceWithLabel("busy-res", "gpu-pool");
        manager.createResourceWithLabel("other-res", "cpu-pool");
        LockableResource res = manager.fromName("busy-res");
        res.setReservedBy("tester");

        org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct matchingStruct =
                new org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct(
                        Collections.singletonList("busy-res"), null, 0);
        org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct nonMatchingStruct =
                new org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct(
                        Collections.singletonList("other-res"), null, 0);

        hudson.model.Run<?, ?> holderBuild = j.buildAndAssertSuccess(j.createFreeStyleProject("holder"));

        java.util.concurrent.atomic.AtomicInteger matchingGetBuildCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger nonMatchingGetBuildCalls =
                new java.util.concurrent.atomic.AtomicInteger();

        org.jenkins.plugins.lockableresources.queue.QueuedContextStruct matchingQueued =
                new org.jenkins.plugins.lockableresources.queue.QueuedContextStruct(
                        null, Collections.singletonList(matchingStruct), "busy", null, 0) {
                    @Override
                    public hudson.model.Run<?, ?> getBuild() {
                        matchingGetBuildCalls.incrementAndGet();
                        return holderBuild;
                    }
                };
        org.jenkins.plugins.lockableresources.queue.QueuedContextStruct nonMatchingQueued =
                new org.jenkins.plugins.lockableresources.queue.QueuedContextStruct(
                        null, Collections.singletonList(nonMatchingStruct), "other", null, 0) {
                    @Override
                    public hudson.model.Run<?, ?> getBuild() {
                        nonMatchingGetBuildCalls.incrementAndGet();
                        return holderBuild;
                    }
                };

        java.lang.reflect.Field queueField = LockableResourcesManager.class.getDeclaredField("queuedContexts");
        queueField.setAccessible(true);
        java.util.List<org.jenkins.plugins.lockableresources.queue.QueuedContextStruct> queuedContexts =
                (java.util.List<org.jenkins.plugins.lockableresources.queue.QueuedContextStruct>)
                        queueField.get(manager);
        queuedContexts.add(nonMatchingQueued);
        queuedContexts.add(matchingQueued);

        java.util.logging.Logger lrmLogger =
                java.util.logging.Logger.getLogger(LockableResourcesManager.class.getName());
        java.util.logging.Level previousLevel = lrmLogger.getLevel();
        try {
            lrmLogger.setLevel(java.util.logging.Level.INFO);
            org.junit.jupiter.api.Assertions.assertNull(manager.getAvailableResources(
                    Collections.singletonList(new org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct(
                            Collections.emptyList(), "gpu-pool", 1))));
            assertEquals(0, nonMatchingGetBuildCalls.get());
            assertEquals(0, matchingGetBuildCalls.get());

            lrmLogger.setLevel(java.util.logging.Level.FINE);
            org.junit.jupiter.api.Assertions.assertNull(manager.getAvailableResources(
                    Collections.singletonList(new org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct(
                            Collections.emptyList(), "gpu-pool", 1))));
            assertEquals(0, nonMatchingGetBuildCalls.get());
            assertEquals(1, matchingGetBuildCalls.get());
        } finally {
            lrmLogger.setLevel(previousLevel);
            queuedContexts.clear();
        }
    }

    private static void assertContains(AutoCompletionCandidates c, String... values) {
        assertEquals(new TreeSet<>(Arrays.asList(values)), new TreeSet<>(c.getValues()));
    }
}
