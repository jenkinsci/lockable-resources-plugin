package org.jenkins.plugins.lockableresources;

import static io.jenkins.plugins.casc.misc.Util.*;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.model.CNode;
import java.util.List;
import org.junit.ClassRule;
import org.junit.Test;

public class ConfigurationAsCodeTest {

  @ClassRule
  @ConfiguredWithCode("configuration-as-code.yml")
  public static JenkinsConfiguredWithCodeRule r = new JenkinsConfiguredWithCodeRule();

  @Test
  public void should_support_configuration_as_code() throws Exception {
    List<LockableResource> declaredResources =
        LockableResourcesManager.get().getDeclaredResources();
    assertEquals(
        "The number of declared resources is wrong. Check your configuration-as-code.yml",
        1,
        declaredResources.size());

    LockableResource declaredResouce = declaredResources.get(0);
    assertEquals("Resource_A", declaredResouce.getName());
    assertEquals("Description_A", declaredResouce.getDescription());
    assertEquals("Label_A", declaredResouce.getLabels());
    assertEquals("Reserved_A", declaredResouce.getReservedBy());

    List<LockableResource> resources = LockableResourcesManager.get().getResources();
    assertEquals(
        "The number of resources is wrong. Check your configuration-as-code.yml",
        1,
        resources.size());

    LockableResource resource = resources.get(0);
    assertEquals("Resource_A", resource.getName());
    assertEquals("Description_A", resource.getDescription());
    assertEquals("Label_A", resource.getLabels());
    assertEquals("Reserved_A", resource.getReservedBy());
  }

  @Test
  public void should_support_configuration_export() throws Exception {
    ConfiguratorRegistry registry = ConfiguratorRegistry.get();
    ConfigurationContext context = new ConfigurationContext(registry);
    CNode yourAttribute = getUnclassifiedRoot(context).get("lockableResourcesManager");
    String exported = toYamlString(yourAttribute);
    String expected = toStringFromYamlFile(this, "casc_expected_output.yml");

    assertThat(exported, is(expected));
  }
}
