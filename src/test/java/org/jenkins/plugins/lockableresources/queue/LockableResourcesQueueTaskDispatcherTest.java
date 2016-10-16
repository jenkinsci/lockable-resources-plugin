package org.jenkins.plugins.lockableresources.queue;

import com.google.common.collect.Sets;
import hudson.EnvVars;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import org.jenkins.plugins.lockableresources.Utils;
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
    
    private Job<?, ?> createProjectMock(String name, String fullName) {
        Job<?, ?> project = PowerMockito.mock(Job.class);
        when(project.getName()).thenReturn(name);
        when(project.getFullName()).thenReturn(fullName);
        return project;
    }
    private Queue.Item createItemMock(final Job<?, ?> project, long id) {
        Queue.Item item = PowerMockito.mock(Queue.Item.class);
        when(item.getId()).thenReturn(id);
        when(Utils.getProject(item)).thenReturn(project);
        return item;
    }
    private LockableResourcesManager createLockableResourcesManagerMock() {
        LockableResourcesManager manager = PowerMockito.mock(LockableResourcesManager.class);
        when(LockableResourcesManager.get()).thenReturn(manager);
        return manager;
    }
    private void addManagerBuildData(LockableResourcesManager manager, Job<?, ?> project, Queue.Item item, Set<LockableResource> reservedResources, Collection<RequiredResources> requiredResources) {
        when(manager.getProjectRequiredResources(project)).thenReturn(requiredResources);
        when(manager.queue(project, item)).thenReturn(reservedResources);
    }
    private EnvVars createEnvVarsMock(Queue.Item item) {
        EnvVars env = new EnvVars();
        when(Utils.getEnvVars(item)).thenReturn(env);
        return env;
    }

    @Test
    public void cause_of_blockage_is_null_when_required_resources_is_null() {
        // Given
        Job<?, ?> project = createProjectMock(DEFAULT_PROJECT_NAME, DEFAULT_PROJECT_FULLNAME);
        Queue.Item item = createItemMock(project, DEFAULT_ITEM_ID);

        LockableResourcesManager manager = createLockableResourcesManagerMock();
        addManagerBuildData(manager, project, item, null, null);

        // When
        LockableResourcesQueueTaskDispatcher dispatcher = spy(LockableResourcesQueueTaskDispatcher.class);
        CauseOfBlockage causeOfBlockage = dispatcher.canRun(item);

        // Then
        assertThat(causeOfBlockage).isNull();
    }

    @Test
    public void cause_of_blockage_is_null_when_required_resource_names_and_label_are_empty() {
        // Given
        Job<?, ?> project = createProjectMock(DEFAULT_PROJECT_NAME, DEFAULT_PROJECT_FULLNAME);
        Queue.Item item = createItemMock(project, DEFAULT_ITEM_ID);

        EnvVars env = createEnvVarsMock(item);
        env.put("key", "value");
        
        List<RequiredResources> resources = new ArrayList();
        String label = "";
        String resourceName = "";
        Integer resourceNumber = 0;
        RequiredResources resource = mock(RequiredResources.class);
        resource.setResources(resourceName);
        resource.setLabels(label);
        resource.setQuantity(resourceNumber);
        resources.add(resource);
        when(resource.getResources(env)).thenReturn(resourceName);
        when(resource.getLabels(env)).thenReturn(label);
        when(resource.getQuantity()).thenReturn(resourceNumber);

        LockableResourcesManager manager = createLockableResourcesManagerMock();
        addManagerBuildData(manager, project, item, new HashSet<LockableResource>(), resources);

        // When
        LockableResourcesQueueTaskDispatcher dispatcher = spy(LockableResourcesQueueTaskDispatcher.class);
        CauseOfBlockage causeOfBlockage = dispatcher.canRun(item);

        // Then
        assertThat(causeOfBlockage).isNull();
    }

    @Test
    public void should_parse_required_number_even_if_number_format_exception_is_thrown() {
        // Given
        Job<?, ?> project = createProjectMock(DEFAULT_PROJECT_NAME, DEFAULT_PROJECT_FULLNAME);
        Queue.Item item = createItemMock(project, DEFAULT_ITEM_ID);

        EnvVars env = createEnvVarsMock(item);
        env.put("key", "value");
        
        RequiredResources resource = mock(RequiredResources.class);
        String label = "label";
        String resourceName = "resource-name";
        Integer resourceNumber = null;
        resource.setResources(label);
        resource.setLabels(resourceName);
        resource.setQuantity(null); //"NOT_A_NUMBER";
        List<RequiredResources> resources = new ArrayList<>();
        resources.add(resource);
        when(resource.getResources(env)).thenReturn(resourceName);
        when(resource.getLabels(env)).thenReturn(label);
        when(resource.getQuantity()).thenReturn(resourceNumber);

        HashSet<LockableResource> reservedResources = Sets.newHashSet();
        
        LockableResourcesManager manager = createLockableResourcesManagerMock();
        addManagerBuildData(manager, project, item, reservedResources, resources);
        
        // When
        LockableResourcesQueueTaskDispatcher dispatcher = spy(LockableResourcesQueueTaskDispatcher.class);
        CauseOfBlockage causeOfBlockage = dispatcher.canRun(item);

        // Then
        verify(manager).queue(project, item);
        assertThat(causeOfBlockage).isNull();
    }

    @Test
    public void cause_of_blockage_is_null_when_required_resources_have_been_reserved() {
        // Given
        Job<?, ?> project = createProjectMock(DEFAULT_PROJECT_NAME, DEFAULT_PROJECT_FULLNAME);
        Queue.Item item = createItemMock(project, DEFAULT_ITEM_ID);

        EnvVars env = createEnvVarsMock(item);
        env.put("key", "value");
        
        RequiredResources resource = mock(RequiredResources.class);
        String label = "";
        String resourceName = "resource-name";
        Integer resourceNumber = 1;
        resource.setResources(label);
        resource.setLabels(resourceName);
        resource.setQuantity(resourceNumber);
        List<RequiredResources> resources = new ArrayList<>();
        resources.add(resource);
        when(resource.getResources(env)).thenReturn(resourceName);
        when(resource.getLabels(env)).thenReturn(label);
        when(resource.getQuantity()).thenReturn(resourceNumber);

        HashSet<LockableResource> reservedResources = Sets.newHashSet(new LockableResource("resource"));

        LockableResourcesManager manager = createLockableResourcesManagerMock();
        addManagerBuildData(manager, project, item, reservedResources, resources);

        // When
        LockableResourcesQueueTaskDispatcher dispatcher = spy(LockableResourcesQueueTaskDispatcher.class);
        CauseOfBlockage causeOfBlockage = dispatcher.canRun(item);

        // Then
        verify(manager).queue(project, item);
        assertThat(causeOfBlockage).isNull();
    }

    @Test
    public void should_get_cause_of_blockage_with_label_when_required_resources_have_not_been_reserved() {
        // Given
        Job<?, ?> project = createProjectMock(DEFAULT_PROJECT_NAME, DEFAULT_PROJECT_FULLNAME);
        Queue.Item item = createItemMock(project, DEFAULT_ITEM_ID);

        EnvVars env = createEnvVarsMock(item);
        env.put("key", "value");

        RequiredResources resource = mock(RequiredResources.class);
        String label = "label";
        String resourceName = "";
        Integer resourceNumber = 0;
        resource.setResources(resourceName);
        resource.setLabels(label);
        resource.setQuantity(resourceNumber);
        when(resource.getResources(env)).thenReturn(resourceName);
        when(resource.getLabels(env)).thenReturn(label);
        when(resource.getQuantity()).thenReturn(resourceNumber);

        List<RequiredResources> resources = new ArrayList<>();
        resources.add(resource);

        LockableResourcesManager manager = createLockableResourcesManagerMock();
        addManagerBuildData(manager, project, item, null, resources);

        // When
        LockableResourcesQueueTaskDispatcher dispatcher = spy(LockableResourcesQueueTaskDispatcher.class);
        CauseOfBlockage causeOfBlockage = dispatcher.canRun(item);

        // Then
        assertThat(causeOfBlockage).isNotNull();
        assertThat(causeOfBlockage.getShortDescription()).isEqualTo("Waiting for resources " + label);
    }

    @Test
    public void should_get_cause_of_blockage_with_resource_names_when_required_resources_have_not_been_reserved() {
        // Given
        Job<?, ?> project = createProjectMock(DEFAULT_PROJECT_NAME, DEFAULT_PROJECT_FULLNAME);
        Queue.Item item = createItemMock(project, DEFAULT_ITEM_ID);

        EnvVars env = createEnvVarsMock(item);
        env.put("key", "value");
        
        RequiredResources resource = mock(RequiredResources.class);
        String label = "";
        String resourceName = "resource-name";
        Integer resourceNumber = 0;
        resource.setResources(resourceName);
        resource.setLabels(label);
        resource.setQuantity(resourceNumber);
        when(resource.getResources(env)).thenReturn(resourceName);
        when(resource.getLabels(env)).thenReturn(label);
        when(resource.getQuantity()).thenReturn(resourceNumber);
        
        List<RequiredResources> resources = new ArrayList<>();
        resources.add(resource);

        LockableResourcesManager manager = createLockableResourcesManagerMock();
        addManagerBuildData(manager, project, item, null, resources);

        // When
        LockableResourcesQueueTaskDispatcher dispatcher = spy(LockableResourcesQueueTaskDispatcher.class);
        CauseOfBlockage causeOfBlockage = dispatcher.canRun(item);

        // Then
        verify(manager).queue(project, item);
        assertThat(causeOfBlockage).isNotNull();
        assertThat(causeOfBlockage.getShortDescription()).isEqualTo("Waiting for resources " + resourceName);
    }
}
