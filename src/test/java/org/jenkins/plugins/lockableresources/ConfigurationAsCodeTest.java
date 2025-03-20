package org.jenkins.plugins.lockableresources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.Util;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import io.jenkins.plugins.casc.model.CNode;
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
        List<LockableResource> declaredResources = LRM.getDeclaredResources();
        assertEquals(
                1,
                declaredResources.size(),
                "The number of declared resources is wrong. Check your configuration-as-code.yml");

        LockableResource declaredResource = declaredResources.get(0);
        assertEquals("Resource_A", declaredResource.getName());
        assertEquals("Description_A", declaredResource.getDescription());
        assertEquals("Label_A", declaredResource.getLabels());
        assertEquals("Reserved_A", declaredResource.getReservedBy());
        assertEquals("Note A", declaredResource.getNote());

        assertEquals(
                1, LRM.getResources().size(), "The number of resources is wrong. Check your configuration-as-code.yml");

        LockableResource resource = LRM.getFirst();
        assertEquals("Resource_A", resource.getName());
        assertEquals("Description_A", resource.getDescription());
        assertEquals("Label_A", resource.getLabels());
        assertEquals("Reserved_A", resource.getReservedBy());
        assertEquals("Note A", resource.getNote());
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
}
