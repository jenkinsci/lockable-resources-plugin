package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.security.ACL;
import hudson.security.AccessDeniedException3;
import hudson.util.FormValidation;
import java.net.URL;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ResourceGroupConfigurationTest {
    @Test
    void groupNameValidationRequiresAdminAndPost(JenkinsRule j) throws Exception {
        ResourceGroup.DescriptorImpl descriptor = j.jenkins.getDescriptorByType(ResourceGroup.DescriptorImpl.class);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckName("plc").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckName("  ").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckName(null).kind);

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .toEveryone()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin"));
        j.jenkins.setCrumbIssuer(null);
        try (var ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
            assertThrows(AccessDeniedException3.class, () -> descriptor.doCheckName("plc"));
        }
        URL endpoint = j.getURL()
                .toURI()
                .resolve("descriptorByName/" + ResourceGroup.class.getName() + "/checkName?value=plc")
                .toURL();

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
            wc.login("reader");
            assertEquals(
                    403,
                    wc.getPage(new WebRequest(endpoint, HttpMethod.POST))
                            .getWebResponse()
                            .getStatusCode());
            wc.login("admin");
            assertEquals(
                    405,
                    wc.getPage(new WebRequest(endpoint, HttpMethod.GET))
                            .getWebResponse()
                            .getStatusCode());
            assertEquals(
                    200,
                    wc.getPage(new WebRequest(endpoint, HttpMethod.POST))
                            .getWebResponse()
                            .getStatusCode());
        }
    }

    @Test
    void groupMetadataAndEmptyGroupsSurviveGlobalFormRoundtrip(JenkinsRule j) throws Exception {
        LockableResourcesManager manager = LockableResourcesManager.get();
        ResourceGroup group = new ResourceGroup(" plc ");
        group.setDescription("Team-owned PLC devices");
        manager.setResourceGroups(List.of(group, new ResourceGroup("empty")));
        LockableResource resource = new LockableResource("plc-1");
        resource.setGroup("plc");
        manager.setDeclaredResources(List.of(resource));

        j.configRoundtrip();

        assertEquals(
                List.of("common", "empty", "plc"),
                manager.getResourceGroups().stream().map(ResourceGroup::getName).toList());
        assertEquals(
                "Team-owned PLC devices", manager.getResourceGroups().get(2).getDescription());
        assertEquals("plc", manager.fromName("plc-1").getGroup());
        assertEquals(
                List.of("common", "empty", "plc"),
                List.copyOf(new LockableResourcesRootAction().getGroups().keySet()));
        assertEquals(0, new LockableResourcesRootAction().getGroups().get("empty"));
        assertEquals(1, new LockableResourcesRootAction().getGroups().get("plc"));
    }

    @Test
    void rejectsBlankAndDuplicateNamesWithoutReplacingCatalog(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        assertThrows(IllegalArgumentException.class, () -> new ResourceGroup("  "));
        assertThrows(
                IllegalArgumentException.class,
                () -> manager.setResourceGroups(List.of(new ResourceGroup("plc"), new ResourceGroup(" plc "))));
        assertEquals(
                List.of("common"),
                manager.getResourceGroups().stream().map(ResourceGroup::getName).toList());
    }

    @Test
    void referencedGroupsCannotDisappearByRemovingMetadata(JenkinsRule j) {
        LockableResourcesManager manager = LockableResourcesManager.get();
        LockableResource resource = new LockableResource("plc-1");
        resource.setGroup("plc");
        manager.setDeclaredResources(List.of(resource));
        manager.setResourceGroups(List.of());
        assertTrue(manager.getGroups().contains("plc"));
        assertEquals("plc", manager.fromName("plc-1").getGroup());
    }
}
