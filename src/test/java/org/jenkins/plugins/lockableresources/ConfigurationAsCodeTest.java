package org.jenkins.plugins.lockableresources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.Util;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import io.jenkins.plugins.casc.model.CNode;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@WithJenkinsConfiguredWithCode
class ConfigurationAsCodeTest {

    // ---------------------------------------------------------------------------
    @BeforeEach
    void setUp() {
        // to speed up the test
        System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    }

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void should_support_configuration_as_code(JenkinsConfiguredWithCodeRule r) {
        LockableResourcesManager LRM = LockableResourcesManager.get();
        assertTrue(LRM.isAllowEmptyOrNullValues());
        assertTrue(LRM.isAllowEphemeralResources());

        List<LockableResource> declaredResources = LRM.getDeclaredResources();
        assertEquals(
                3,
                declaredResources.size(),
                "The number of declared resources is wrong. Check your configuration-as-code.yml");

        LockableResource declaredResource = declaredResources.get(0);
        assertEquals("Resource_A", declaredResource.getName());
        assertEquals("Description_A", declaredResource.getDescription());
        assertEquals("Label_A", declaredResource.getLabelsAsString());
        // not supported in JCaC
        assertNull(declaredResource.getReservedBy());
        assertEquals("", declaredResource.getNote());

        assertEquals(
                3, LRM.getResources().size(), "The number of resources is wrong. Check your configuration-as-code.yml");

        LockableResource resource = LRM.getFirst();
        assertEquals("Resource_A", resource.getName());
        assertEquals("Description_A", resource.getDescription());
        assertEquals("Label_A", resource.getLabelsAsString());
        // not supported in JCaC
        assertNull(declaredResource.getReservedBy());
        assertEquals("", declaredResource.getNote());
    }

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void should_support_configuration_export(JenkinsConfiguredWithCodeRule r) throws Exception {
        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);
        CNode yourAttribute = Util.getUnclassifiedRoot(context).get("lockableResourcesManager");
        String exported = Util.toYamlString(yourAttribute);
        String expected = Util.toStringFromYamlFile(this, "casc_expected_output.yml");

        assertThat(exported, is(expected));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void should_keep_reservations_after_reload(JenkinsConfiguredWithCodeRule r) {
        LockableResourcesManager LRM = LockableResourcesManager.get();

        LRM.reserve(Collections.singletonList(LRM.fromName("Resource_B")), "testUser");
        assertEquals("testUser", LRM.fromName("Resource_B").getReservedBy());
        Date timestampBeforeReload = LRM.fromName("Resource_B").getReservedTimestamp();
        String noteBeforeReload = LRM.fromName("Resource_B").getNote();

        // Get current sources and reconfigure with those sources
        List<String> srcs = ConfigurationAsCode.get().getSources();
        ConfigurationAsCode.get().configure(srcs);

        assertEquals("testUser", LRM.fromName("Resource_B").getReservedBy());
        assertEquals(timestampBeforeReload, LRM.fromName("Resource_B").getReservedTimestamp());
        assertEquals(noteBeforeReload, LRM.fromName("Resource_B").getNote());
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-ephemeral-disabled.yml")
    void should_support_ephemeral_resources_disabled_via_casc(JenkinsConfiguredWithCodeRule r) {
        LockableResourcesManager LRM = LockableResourcesManager.get();
        assertTrue(LRM.isAllowEmptyOrNullValues());
        assertThat(LRM.isAllowEphemeralResources(), is(false));

        List<LockableResource> declaredResources = LRM.getDeclaredResources();
        assertEquals(1, declaredResources.size());
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-remote.yml")
    void should_support_remote_server_side_config_via_casc(JenkinsConfiguredWithCodeRule r) {
        LockableResourcesManager LRM = LockableResourcesManager.get();
        assertTrue(LRM.isRemoteApiEnabled());
        assertEquals("remote-enabled", LRM.getExposeLabel());
        assertFalse(LRM.isAcceptNewAcquires());
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-remote.yml")
    void should_support_remote_client_side_config_via_casc(JenkinsConfiguredWithCodeRule r) {
        LockableResourcesManager LRM = LockableResourcesManager.get();
        assertEquals("jenkins-client-1", LRM.getClientId());
        assertEquals("", LRM.getForcedServerId());
        List<RemoteConnection> remotes = LRM.getRemotes();
        assertEquals(2, remotes.size());
        assertEquals("server-a", remotes.get(0).getServerId());
        assertTrue(remotes.get(0).isEnabled(), "omitting enabled keeps the connection usable");
        assertFalse(remotes.get(1).isEnabled(), "enabled: false round-trips");
        assertEquals("http://jenkins-server-a:8080/jenkins", remotes.get(0).getUrl());
        assertEquals("server-a-token", remotes.get(0).getCredentialsId());
        assertEquals("server-b", remotes.get(1).getServerId());
        assertEquals("https://jenkins-server-b.example.com/", remotes.get(1).getUrl());
        assertEquals("server-b-token", remotes.get(1).getCredentialsId());
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-remote.yml")
    void should_support_remote_resources_via_casc(JenkinsConfiguredWithCodeRule r) {
        LockableResourcesManager LRM = LockableResourcesManager.get();
        List<LockableResource> declared = LRM.getDeclaredResources();
        assertEquals(2, declared.size());
        assertEquals("plc-01", declared.get(0).getName());
        assertEquals("plc remote-enabled", declared.get(0).getLabelsAsString());
        assertEquals("plc-02", declared.get(1).getName());
    }
}
