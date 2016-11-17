package org.jenkins.plugins.lockableresources;

import com.google.common.collect.Sets;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest({Utils.class, Job.class, Queue.Item.class, LockableResourcesManager.class})
public class LockableResourcesManagerTest {
    @Before
    public void setup() {
        PowerMockito.mockStatic(Utils.class);
        PowerMockito.mockStatic(LockableResourcesManager.class);
    }

    @Test
    public void should_get_resources_from_project() throws Exception {
        // Given
        String fullName = "my-project";
        String queueItemProject = "other-project";
        Job<?, ?> project1 = Mockups.createProjectMock("Project2", queueItemProject);
        Job<?, ?> project2 = Mockups.createProjectMock("Project1", fullName);
        Queue.Item item1_1 = Mockups.createQueuedItemMock(project1, 1);
        Queue.Item item2_1 = Mockups.createQueuedItemMock(project2, 1);
        Queue.Item item2_2 = Mockups.createQueuedItemMock(project2, 2);

        LockableResource resource1 = Mockups.createQueuedLockableResourceMock("resource1", "desc", "r1", project1, item1_1);
        LockableResource resource2 = Mockups.createQueuedLockableResourceMock("resource2", "desc", "r2", project2, item2_1);
        LockableResource resource3 = Mockups.createQueuedLockableResourceMock("resource3", "desc", "r3", project2, item2_2);

        Set<LockableResource> resources = Sets.newHashSet();
        resources.add(resource1);
        resources.add(resource2);
        resources.add(resource3);

        // When
        LockableResourcesManager lockableResourcesManager = Mockups.createLockableResourcesManagerMock(resources);
        Mockito.when(lockableResourcesManager.getQueuedResourcesFromProject(fullName)).thenCallRealMethod();
        Set<LockableResource> matching = lockableResourcesManager.getQueuedResourcesFromProject(fullName);

        // Then
        Mockito.verify(resource1).getQueueItemProject();
        Mockito.verify(resource2).getQueueItemProject();
        Mockito.verify(resource3).getQueueItemProject();

        Assertions.assertThat(matching).containsExactlyInAnyOrder(resource2, resource3);
    }

    @Test
    public void should_get_resources_from_build() throws Exception {
        // Given
        Job<?, ?> project = Mockups.createProjectMock("Project", "MyProject");
        Queue.Item item = Mockups.createQueuedItemMock(project, 1);
        Run build = Mockups.createBuildMock(item, null);

        LockableResource resource1 = Mockups.createLockedLockableResourceMock("resource1", "desc", "r1", project, item, build);
        LockableResource resource2 = Mockups.createQueuedLockableResourceMock("resource2", "desc", "r2", project, item);
        LockableResource resource3 = Mockups.createFreeLockableResourceMock("resource3", "desc", "r3");
        LockableResource resource4 = Mockups.createReservedLockableResourceMock("resource4", "desc", "r4", "ReservedBySomeone");

        Set<LockableResource> resources = Sets.newHashSet();
        resources.add(resource1);
        resources.add(resource2);
        resources.add(resource3);
        resources.add(resource4);

        // When
        LockableResourcesManager manager = Mockups.createLockableResourcesManagerMock(resources);
        Mockito.when(manager.getLockedResourcesFromBuild(build)).thenCallRealMethod();
        Set<LockableResource> matching = manager.getLockedResourcesFromBuild(build);

        // Then
        Assertions.assertThat(matching).containsExactly(resource1);
    }
}
