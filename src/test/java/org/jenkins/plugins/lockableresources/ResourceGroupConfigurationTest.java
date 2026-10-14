package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ResourceGroupConfigurationTest {
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
