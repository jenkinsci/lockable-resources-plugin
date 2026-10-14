package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class ResourceGroupMigrationTest {
    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void legacyXmlMigratesToCommonAndRetainsExistingLabelSelection() throws Throwable {
        System.clearProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE);
        sessions.then(j -> {
            try (var xml = getClass().getResourceAsStream("legacy-resources.xml")) {
                assertNotNull(xml);
                Files.copy(
                        xml,
                        j.jenkins.getRootDir().toPath().resolve(LockableResourcesManager.class.getName() + ".xml"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        });
        sessions.then(j -> {
            LockableResourcesManager manager = LockableResourcesManager.get();
            assertEquals(2, manager.getDeclaredResources().size());
            assertEquals(
                    List.of("common"),
                    manager.getResourceGroups().stream()
                            .map(ResourceGroup::getName)
                            .toList());
            LockableResource printer = manager.fromName("legacy-printer");
            assertEquals("common", printer.getGroup());
            assertEquals("Existing shared printer", printer.getDescription());
            assertEquals(List.of("printer", "office"), printer.getLabelsAsList());
            assertEquals("operator", printer.getReservedBy());
            assertEquals("Keep this reservation", printer.getNote());
            assertEquals("common", manager.fromName("legacy-device").getGroup());
            WorkflowJob job = j.createProject(WorkflowJob.class, "existing-label-request");
            job.setDefinition(new CpsFlowDefinition("""
                    lock(label: 'printer', quantity: 1, variable: 'DEVICE') {
                      echo "selected=${env.DEVICE}"
                    }
                    """, true));
            j.assertLogContains("selected=legacy-device", j.buildAndAssertSuccess(job));
            manager.save();
        });
        sessions.then(j -> {
            LockableResourcesManager manager = LockableResourcesManager.get();
            String xml = Files.readString(
                    j.jenkins.getRootDir().toPath().resolve(LockableResourcesManager.class.getName() + ".xml"));
            assertTrue(xml.contains("<resourceGroups>"));
            assertTrue(xml.contains("<group>common</group>"));
            assertEquals("operator", manager.fromName("legacy-printer").getReservedBy());
            assertEquals("common", manager.fromName("legacy-device").getGroup());
        });
    }
}
