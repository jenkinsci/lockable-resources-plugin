package org.jenkins.plugins.lockableresources.queue;

import com.google.common.collect.Sets;
import hudson.EnvVars;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import org.jenkins.plugins.lockableresources.Utils;
import org.jenkins.plugins.lockableresources.Mockups;
import org.jenkins.plugins.lockableresources.queue.context.QueueItemContext;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.resources.RequiredResources;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.Mockito.*;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest({Utils.class, Job.class, Queue.Item.class, LockableResourcesManager.class, LockableResourcesQueueTaskDispatcher.class})
public class LockableResourcesQueueTaskDispatcherTest {
    private static final String DEFAULT_PROJECT_NAME = "name";
    private static final String DEFAULT_PROJECT_FULLNAME = "full-name";
    private static final long DEFAULT_ITEM_ID = 1;

    @Before
    public void setup() {
        PowerMockito.mockStatic(Utils.class);
        PowerMockito.mockStatic(LockableResourcesManager.class);
    }

    @Test
    public void cause_of_blockage_is_null_when_no_locakble_resources_property() throws Exception {
        // Given
        Job<?, ?> project = Mockups.createProjectMock(DEFAULT_PROJECT_NAME, DEFAULT_PROJECT_FULLNAME);
        Queue.Item item = Mockups.createQueuedItemMock(project, DEFAULT_ITEM_ID);

        LockableResourcesManager manager = Mockups.createLockableResourcesManagerMock(null);

        // When
        LockableResourcesQueueTaskDispatcher dispatcher = spy(LockableResourcesQueueTaskDispatcher.class);
        CauseOfBlockage causeOfBlockage = dispatcher.canRun(item);
        when(manager.tryQueue(project, new QueueItemContext(item))).thenReturn(true);

        // Then
        assertThat(causeOfBlockage).isNull();
    }

    @Test
    public void cause_of_blockage_is_null_when_required_resources_is_null() throws Exception {
        // Given
        Job<?, ?> project = Mockups.createProjectMock(DEFAULT_PROJECT_NAME, DEFAULT_PROJECT_FULLNAME);
        Queue.Item item = Mockups.createQueuedItemMock(project, DEFAULT_ITEM_ID);

        LockableResourcesManager manager = Mockups.createLockableResourcesManagerMock(null);
        Mockups.createPropertyMock(project, null, null);
        when(manager.tryQueue(project, new QueueItemContext(item))).thenReturn(true);

        // When
        LockableResourcesQueueTaskDispatcher dispatcher = spy(LockableResourcesQueueTaskDispatcher.class);
        CauseOfBlockage causeOfBlockage = dispatcher.canRun(item);

        // Then
        assertThat(causeOfBlockage).isNull();
    }

    @Test
    public void cause_of_blockage_is_null_when_required_resource_names_and_label_are_empty() throws Exception {
        // Given
        Job<?, ?> project = Mockups.createProjectMock(DEFAULT_PROJECT_NAME, DEFAULT_PROJECT_FULLNAME);
        Queue.Item item = Mockups.createQueuedItemMock(project, DEFAULT_ITEM_ID);

        EnvVars env = Mockups.createEnvVarsMock(item);
        env.put("key", "value");

        String label = "";
        String resourceName = "";
        Integer resourceNumber = 0;
        RequiredResources resource = new RequiredResources(resourceName, label, resourceNumber);
        List<RequiredResources> resources = new ArrayList();
        resources.add(resource);
        
        LockableResourcesManager manager = Mockups.createLockableResourcesManagerMock(null);
        Mockups.createPropertyMock(project, resources, null);
        when(manager.tryQueue(project, new QueueItemContext(item))).thenReturn(true);

        // When
        LockableResourcesQueueTaskDispatcher dispatcher = spy(LockableResourcesQueueTaskDispatcher.class);
        CauseOfBlockage causeOfBlockage = dispatcher.canRun(item);

        // Then
        assertThat(causeOfBlockage).isNull();
    }

    @Test
    public void should_parse_required_number_even_if_number_format_exception_is_thrown() throws Exception {
        // Given
        Job<?, ?> project = Mockups.createProjectMock(DEFAULT_PROJECT_NAME, DEFAULT_PROJECT_FULLNAME);
        Queue.Item item = Mockups.createQueuedItemMock(project, DEFAULT_ITEM_ID);

        EnvVars env = Mockups.createEnvVarsMock(item);
        env.put("key", "value");

        String label = "label";
        String resourceName = "resource-name";
        Integer resourceNumber = null; //"NOT_A_NUMBER";
        RequiredResources resource = new RequiredResources(resourceName, label, resourceNumber);
        List<RequiredResources> resources = new ArrayList();
        resources.add(resource);

        LockableResourcesManager manager = Mockups.createLockableResourcesManagerMock(null);
        Mockups.createPropertyMock(project, resources, null);
        when(manager.tryQueue(project, new QueueItemContext(item))).thenReturn(true);

        // When
        LockableResourcesQueueTaskDispatcher dispatcher = spy(LockableResourcesQueueTaskDispatcher.class);
        CauseOfBlockage causeOfBlockage = dispatcher.canRun(item);

        // Then
        verify(manager).tryQueue(project, new QueueItemContext(item));
        assertThat(causeOfBlockage).isNull();
    }

    @Test
    public void cause_of_blockage_is_null_when_required_resources_have_been_reserved() throws Exception {
        // Given
        Job<?, ?> project = Mockups.createProjectMock(DEFAULT_PROJECT_NAME, DEFAULT_PROJECT_FULLNAME);
        Queue.Item item = Mockups.createQueuedItemMock(project, DEFAULT_ITEM_ID);

        EnvVars env = Mockups.createEnvVarsMock(item);
        env.put("key", "value");

        String label = "";
        String resourceName = "resource-name";
        Integer resourceNumber = 1;
        RequiredResources resource = new RequiredResources(resourceName, label, resourceNumber);
        List<RequiredResources> resources = new ArrayList();
        resources.add(resource);

        HashSet<LockableResource> reservedResources = Sets.newHashSet(new LockableResource("resource"));

        LockableResourcesManager manager = Mockups.createLockableResourcesManagerMock(null);
        Mockups.createPropertyMock(project, resources, null);
        when(manager.tryQueue(project, new QueueItemContext(item))).thenReturn(true);

        // When
        LockableResourcesQueueTaskDispatcher dispatcher = spy(LockableResourcesQueueTaskDispatcher.class);
        CauseOfBlockage causeOfBlockage = dispatcher.canRun(item);

        // Then
        verify(manager).tryQueue(project, new QueueItemContext(item));
        assertThat(causeOfBlockage).isNull();
    }

    @Test
    public void should_get_cause_of_blockage_with_label_when_required_resources_have_not_been_reserved() throws Exception {
        // Given
        Job<?, ?> project = Mockups.createProjectMock(DEFAULT_PROJECT_NAME, DEFAULT_PROJECT_FULLNAME);
        Queue.Item item = Mockups.createQueuedItemMock(project, DEFAULT_ITEM_ID);

        EnvVars env = Mockups.createEnvVarsMock(item);
        env.put("key", "value");

        String label = "label";
        String resourceName = "";
        Integer resourceNumber = 0;
        RequiredResources resource = new RequiredResources(resourceName, label, resourceNumber);
        List<RequiredResources> resources = new ArrayList<>();
        resources.add(resource);

        LockableResourcesManager manager = Mockups.createLockableResourcesManagerMock(null);
        Mockups.createPropertyMock(project, resources, null);
        when(manager.tryQueue(project, new QueueItemContext(item))).thenReturn(false);

        // When
        LockableResourcesQueueTaskDispatcher dispatcher = spy(LockableResourcesQueueTaskDispatcher.class);
        CauseOfBlockage causeOfBlockage = dispatcher.canRun(item);

        // Then
        assertThat(causeOfBlockage).isNotNull();
        assertThat(causeOfBlockage.getShortDescription()).isEqualTo("Waiting for resources " + label);
    }

    @Test
    public void should_get_cause_of_blockage_with_resource_names_when_required_resources_have_not_been_reserved() throws Exception {
        // Given
        Job<?, ?> project = Mockups.createProjectMock(DEFAULT_PROJECT_NAME, DEFAULT_PROJECT_FULLNAME);
        Queue.Item item = Mockups.createQueuedItemMock(project, DEFAULT_ITEM_ID);

        EnvVars env = Mockups.createEnvVarsMock(item);
        env.put("key", "value");

        String label = "";
        String resourceName = "resource-name";
        Integer resourceNumber = 0;
        RequiredResources resource = new RequiredResources(resourceName, label, resourceNumber);
        List<RequiredResources> resources = new ArrayList<>();
        resources.add(resource);

        LockableResourcesManager manager = Mockups.createLockableResourcesManagerMock(null);
        Mockups.createPropertyMock(project, resources, null);
        when(manager.tryQueue(project, new QueueItemContext(item))).thenReturn(false);

        // When
        LockableResourcesQueueTaskDispatcher dispatcher = spy(LockableResourcesQueueTaskDispatcher.class);
        CauseOfBlockage causeOfBlockage = dispatcher.canRun(item);

        // Then
        verify(manager).tryQueue(project, new QueueItemContext(item));
        assertThat(causeOfBlockage).isNotNull();
        assertThat(causeOfBlockage.getShortDescription()).isEqualTo("Waiting for resources " + resourceName);
    }
}
