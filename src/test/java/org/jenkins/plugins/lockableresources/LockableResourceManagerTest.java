package org.jenkins.plugins.lockableresources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import hudson.model.AutoCompletionCandidates;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.User;
import hudson.security.AccessDeniedException3;
import hudson.util.FormValidation;
import java.util.Arrays;
import java.util.Collections;
import java.util.TreeSet;
import jenkins.model.Jenkins;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.springframework.security.core.context.SecurityContextHolder;

public class LockableResourceManagerTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void validationFailure() throws Exception {
        ParametersDefinitionProperty params = new ParametersDefinitionProperty(
                new StringParameterDefinition("param1", "resource1", "parameter 1"),
                new StringParameterDefinition("param2", "2", "parameter 2"));
        FreeStyleProject p = j.createFreeStyleProject("p");
        p.addProperty(params);

        RequiredResourcesProperty.DescriptorImpl d = new RequiredResourcesProperty.DescriptorImpl();
        LockableResourcesManager.get().createResource("resource1");
        LockableResource r = LockableResourcesManager.get().getFirst();
        r.setLabels("some-label");

        assertEquals(
                "Only resource label, groovy expression, or resource names can be defined, not more than one.",
                d.doCheckResourceNames("resource1", null, true, p).getMessage());
        assertEquals(
                "Only resource label, groovy expression, or resource names can be defined, not more than one.",
                d.doCheckResourceNames("resource1", "some-label", false, p).getMessage());
        assertEquals(
                "Only resource label, groovy expression, or resource names can be defined, not more than one.",
                d.doCheckResourceNames("resource1", "some-label", true, p).getMessage());
        assertEquals(
                "Only resource label, groovy expression, or resource names can be defined, not more than one.",
                d.doCheckLabelName("some-label", "resource1", false, p).getMessage());
        assertEquals(
                "Only resource label, groovy expression, or resource names can be defined, not more than one.",
                d.doCheckLabelName("some-label", null, true, p).getMessage());
        assertEquals(
                "Only resource label, groovy expression, or resource names can be defined, not more than one.",
                d.doCheckLabelName("some-label", "resource1", true, p).getMessage());

        assertEquals(FormValidation.ok(), d.doCheckResourceNames("resource1", null, false, p));
        assertEquals(FormValidation.ok(), d.doCheckLabelName("some-label", null, false, p));
        assertEquals(FormValidation.ok(), d.doCheckResourceNumber("1", "resource1", null, null, p));

        assertEquals(
                "The resource does not exist: [resource3].",
                d.doCheckResourceNames("${param5} resource3", null,  false, p).getMessage());
        assertEquals(
                "The parameter does not exist: [param5, param4].",
                d.doCheckResourceNames("${param5} ${param4} resource1", null,  false, p).getMessage());
        assertEquals(
                "The resource label does not exist: other-label.",
                d.doCheckLabelName("other-label", null, false, p).getMessage());

        assertEquals(
                "The resource cannot be validated as it contains parameters: [${param1}].",
                d.doCheckResourceNames("${param1}", null,  false, p).getMessage());
        assertEquals(
                "The resource cannot be validated as it contains parameters: [xyz_${param1}].",
                d.doCheckResourceNames("xyz_${param1}", null,  false, p).getMessage());
        assertEquals(
                "The resource label cannot be validated as it contains parameters: ${param1}.",
                d.doCheckLabelName("${param1}", null, false, p).getMessage());
        assertEquals(
                "The resource label cannot be validated as it contains parameters: ${param1}${param2}.",
                d.doCheckLabelName("${param1}${param2}", null, false, p).getMessage());
        assertEquals(
                "The resource label cannot be validated as it contains parameters: resource${param2}.",
                d.doCheckLabelName("resource${param2}", null, false, p).getMessage());
        assertEquals(
                "The value cannot be validated as it is a parameter value: ${param1}.",
                d.doCheckResourceNumber("${param1}", null,null,  null, p).getMessage());

        assertEquals(
                "Could not parse the given value as integer.",
                d.doCheckResourceNumber("${param1}${param2}", null,null,  null, p).getMessage());
        assertEquals(
                "Could not parse the given value as integer.",
                d.doCheckResourceNumber("Five", null,null,  null, p).getMessage());

        assertEquals(
                "Given amount 4 is greater than amount of resources: 1.",
                d.doCheckResourceNumber("4", "resource1", null,null, p).getMessage());
        assertEquals(
                "Given amount 5 is greater than amount of resources: 1.",
                d.doCheckResourceNumber("5", null, "some-label",null, p).getMessage());

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

        FreeStyleProject item = j.createFreeStyleProject("aProject");
        j.buildAndAssertSuccess(item);
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
    public void doAutoCompleteLabelName() throws Exception {
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
    public void doAutoCompleteResourceNames() throws Exception {
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
        assertThrows(AccessDeniedException3.class, () -> d.doAutoCompleteResourceNames("resource1", item));

        User manager = User.get("manager", true, Collections.emptyMap());
        SecurityContextHolder.getContext().setAuthentication(manager.impersonate2());

        assertContains(d.doAutoCompleteResourceNames("Res", item), "Resource1");
        assertContains(d.doAutoCompleteResourceNames("res", item), "resource1", "resource2");
        d.doAutoCompleteResourceNames(null, item);
        d.doAutoCompleteResourceNames("", item);
    }

    private void assertContains(AutoCompletionCandidates c, String... values) {
        assertEquals(new TreeSet<>(Arrays.asList(values)), new TreeSet<>(c.getValues()));
    }
}
