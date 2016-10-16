package org.jenkins.plugins.lockableresources;

import com.google.common.collect.Sets;
import hudson.EnvVars;
import hudson.model.Run;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.*;
import static org.springframework.test.util.ReflectionTestUtils.setField;

public class LockableResourcesManagerTest {
    private LockableResourcesManager lockableResourcesManager;

    @Before
    public void setUp() {
        lockableResourcesManager = new LockableResourcesManager() {
            @Override
            public synchronized void load() {
                // Nothing to do
            }
        };
    }

    @Test
    public void should_get_resources_from_project() throws Exception {
        // Given
        String fullName = "my-project";
        String label = "r1";
        EnvVars env = new EnvVars();

        LockableResource resource1 = mockLockableResource("resource1", "r1");
        LockableResource resource2 = mockLockableResource("resource2", "r2");
        LockableResource resource3 = mockLockableResource("resource3", "r3");

        Set<LockableResource> resources = Sets.newHashSet();

        resources.add(resource1);
        resources.add(resource2);
        resources.add(resource3);

        setField(lockableResourcesManager, "resources", resources, Set.class);

        String queueItemProject = "other-project";
        
        when(resource1.getQueueItemProject()).thenReturn(queueItemProject);
        when(resource2.getQueueItemProject()).thenReturn(fullName);
        when(resource3.getQueueItemProject()).thenReturn(fullName);

        // When
        Set<LockableResource> matching = lockableResourcesManager.getQueuedResourcesFromProject(fullName);

        // Then
        verify(resource1).getQueueItemProject();
        verify(resource2).getQueueItemProject();
        
        assertThat(matching).containsExactlyInAnyOrder(resource2, resource3);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void should_get_resources_from_build() throws Exception {
        // Given
        Run build = mock(Run.class);

        LockableResource resource1 = mock(LockableResource.class);
        LockableResource resource2 = mock(LockableResource.class);

        Set<LockableResource> resources = Sets.newHashSet();

        resources.add(resource1);
        resources.add(resource2);

        setField(lockableResourcesManager, "resources", resources, Set.class);

        when(resource1.getBuild()).thenReturn(build);

        // When
        Set<LockableResource> matching = lockableResourcesManager.getLockedResourcesFromBuild(build);

        // Then
        assertThat(matching).containsExactly(resource1);
    }

    private LockableResource mockLockableResource(String name, String labels) throws Exception {
        LockableResource resource = mock(LockableResource.class);
        
		setField(resource, "name", name, String.class);
		setField(resource, "labels", labels, String.class);

        return resource;
    }
}
