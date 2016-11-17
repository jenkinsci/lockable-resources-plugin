package org.jenkins.plugins.lockableresources;

import hudson.EnvVars;
import hudson.model.Job;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import javax.annotation.Nonnull;
import org.jenkins.plugins.lockableresources.jobProperty.RequiredResourcesProperty;
import org.jenkins.plugins.lockableresources.queue.policy.QueueFifoPolicy;
import org.jenkins.plugins.lockableresources.queue.policy.QueuePolicy;
import org.jenkins.plugins.lockableresources.resources.LockableResource;
import org.jenkins.plugins.lockableresources.resources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.resources.RequiredResources;
import org.jenkins.plugins.lockableresources.resources.selector.ResourcesDefaultSelector;
import org.jenkins.plugins.lockableresources.resources.selector.ResourcesSelector;
import static org.mockito.Mockito.when;
import org.powermock.api.mockito.PowerMockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Convenient factory to describe the expected behavior of each class
 */
public abstract class Mockups {
    private Mockups() {
    }
    
    //----------------------------------------
    //Jenkins mockups
    //----------------------------------------
    public static final Job<?, ?> createProjectMock(String name, String fullName) {
        Job<?, ?> project = PowerMockito.mock(Job.class);
        when(project.getName()).thenReturn(name);
        when(project.getFullName()).thenReturn(fullName);
        return project;
    }

    public static final Queue.Item createQueuedItemMock(final Job<?, ?> project, long id) {
        Queue.Item item = PowerMockito.mock(Queue.Item.class);
        when(item.getId()).thenReturn(id);
        when(Utils.getProject(item)).thenReturn((Job)project);
        return item;
    }

    public static final Run<?, ?> createBuildMock(final Queue.Item item, TaskListener listener) throws IOException, InterruptedException {
        Run<?, ?> build = PowerMockito.mock(Run.class);
        EnvVars env = createEnvVarsMock(item);
        when(build.getEnvironment(listener)).thenReturn(env);
        Job job = (Job) item.task;
        when(build.getParent()).thenReturn(job);
        when(build.getActions(ParametersAction.class)).thenReturn(new ArrayList<ParametersAction>());
        return build;
    }

    public static final EnvVars createEnvVarsMock(@Nonnull Queue.Item item) {
        EnvVars env = new EnvVars();
        when(Utils.getEnvVars(item)).thenReturn(env);
        return env;
    }

    //----------------------------------------
    // Lockable resources mockups
    //----------------------------------------
    public static final RequiredResourcesProperty createPropertyMock(Job<?, ?> project, Collection<RequiredResources> requiredResourcesList, String variableName) throws Exception {
        RequiredResourcesProperty property = PowerMockito.mock(RequiredResourcesProperty.class);
        when(RequiredResourcesProperty.getFromProject(project)).thenReturn(property);
        when(project.getProperty(RequiredResourcesProperty.class)).thenReturn(property);

        ReflectionTestUtils.setField(property, "requiredResourcesList", requiredResourcesList, Collection.class);
        when(property.getRequiredResourcesList()).thenReturn(requiredResourcesList);

        ReflectionTestUtils.setField(property, "variableName", variableName, String.class);
        when(property.getVariableName()).thenReturn(variableName);
        return property;
    }

    public static final LockableResourcesManager createLockableResourcesManagerMock(Set<LockableResource> resources) throws Exception {
        return createLockableResourcesManagerMock(resources, new ResourcesDefaultSelector(), new QueueFifoPolicy());
    }
    
    public static final LockableResourcesManager createLockableResourcesManagerMock(Set<LockableResource> resources, ResourcesSelector resourcesSelector, QueuePolicy queuePolicy) throws Exception {
        LockableResourcesManager manager = PowerMockito.mock(LockableResourcesManager.class);
        when(LockableResourcesManager.get()).thenReturn(manager);

        ReflectionTestUtils.setField(manager, "resources", resources, Set.class);
        when(manager.getAllResources()).thenReturn(resources);

        ReflectionTestUtils.setField(manager, "resourcesSelector", resourcesSelector, ResourcesSelector.class);
        when(manager.getResourcesSelector()).thenReturn(resourcesSelector);

        ReflectionTestUtils.setField(manager, "resourcesSelector", resourcesSelector, ResourcesSelector.class);
        when(manager.getResourcesSelector()).thenReturn(resourcesSelector);

        ReflectionTestUtils.setField(manager, "queuePolicy", queuePolicy, QueuePolicy.class);
        when(manager.getQueuePolicy()).thenReturn(queuePolicy);

        ReflectionTestUtils.setField(manager, "queuedContexts", new SmartSerializableSet<>(), SmartSerializableSet.class);

        return manager;
    }

    public static final LockableResource createFreeLockableResourceMock(String name, String description, String labels) throws Exception {
        return createLockableResourceMock(name, description, labels, null, null, null, null, null);
    }

    public static final LockableResource createQueuedLockableResourceMock(String name, String description, String labels, Job<?, ?> project, Queue.Item item) throws Exception {
        return createLockableResourceMock(name, description, labels, project, item, null, null, null);
    }

    public static final LockableResource createLockedLockableResourceMock(String name, String description, String labels, Job<?, ?> project, Queue.Item item, Run<?, ?> build) throws Exception {
        return createLockableResourceMock(name, description, labels, project, item, build, null, null);
    }

    public static final LockableResource createReservedLockableResourceMock(String name, String description, String labels, String reservedBy) throws Exception {
        return createLockableResourceMock(name, description, labels, null, null, null, reservedBy, null);
    }

    public static final LockableResource createReservedLockableResourceMock(String name, String description, String labels, String reservedBy, String eMail) throws Exception {
        return createLockableResourceMock(name, description, labels, null, null, null, reservedBy, eMail);
    }

    public static final LockableResource createLockableResourceMock(String name, String description, String labels, Job<?, ?> project, Queue.Item item, Run<?, ?> build, String reservedBy, String email) throws Exception {
        LockableResource resource = PowerMockito.mock(LockableResource.class);

        ReflectionTestUtils.setField(resource, "name", name, String.class);
        when(resource.getName()).thenReturn(name);

        ReflectionTestUtils.setField(resource, "description", description, String.class);
        when(resource.getDescription()).thenReturn(description);

        ReflectionTestUtils.setField(resource, "labels", labels, String.class);
        when(resource.getLabels()).thenReturn(labels);

        ReflectionTestUtils.setField(resource, "build", build, Run.class);
        ReflectionTestUtils.setField(resource, "reservedBy", reservedBy, String.class);

        if(reservedBy != null) {
            ReflectionTestUtils.setField(resource, "queueItemId", 0, long.class);
            ReflectionTestUtils.setField(resource, "queueItemProject", null, String.class);
            ReflectionTestUtils.setField(resource, "buildExternalizableId", null, String.class);
            when(resource.getBuild()).thenReturn(null);
            when(resource.getBuildName()).thenReturn(null);
            when(resource.getQueueItemId()).thenReturn(0L);
            when(resource.getQueueItemProject()).thenReturn(null);
            when(resource.getTask()).thenReturn(null);
            when(resource.getReservedBy()).thenReturn(reservedBy);
            when(resource.getReservedByEmail()).thenReturn(email);
        } else if((project != null) && (item != null) && (build != null)) {
            long id = item.getId();
            String projectName = project.getFullName();
            Queue.Task task = item.task;
            String buildName = build.getFullDisplayName();
            String buildExternalizableId = build.getExternalizableId();
            ReflectionTestUtils.setField(resource, "queueItemId", id, long.class);
            ReflectionTestUtils.setField(resource, "queueItemProject", projectName, String.class);
            ReflectionTestUtils.setField(resource, "buildExternalizableId", buildExternalizableId, String.class);
            when(resource.getBuild()).thenReturn((Run) build);
            when(resource.getBuildName()).thenReturn(buildName);
            when(resource.getQueueItemId()).thenReturn(id);
            when(resource.getQueueItemProject()).thenReturn(projectName);
            when(resource.getTask()).thenReturn(task);
            when(resource.getReservedBy()).thenReturn(null);
            when(resource.getReservedByEmail()).thenReturn(null);
        } else if((project != null) && (item != null)) {
            long id = item.getId();
            String projectName = project.getFullName();
            Queue.Task task = item.task;
            ReflectionTestUtils.setField(resource, "queueItemId", id, long.class);
            ReflectionTestUtils.setField(resource, "queueItemProject", projectName, String.class);
            ReflectionTestUtils.setField(resource, "buildExternalizableId", null, String.class);
            when(resource.getBuild()).thenReturn(null);
            when(resource.getBuildName()).thenReturn(null);
            when(resource.getQueueItemId()).thenReturn(id);
            when(resource.getQueueItemProject()).thenReturn(projectName);
            when(resource.getTask()).thenReturn(task);
            when(resource.getReservedBy()).thenReturn(null);
            when(resource.getReservedByEmail()).thenReturn(null);
        } else {
            ReflectionTestUtils.setField(resource, "queueItemId", 0, long.class);
            ReflectionTestUtils.setField(resource, "queueItemProject", null, String.class);
            ReflectionTestUtils.setField(resource, "buildExternalizableId", null, String.class);
            when(resource.getBuild()).thenReturn(null);
            when(resource.getBuildName()).thenReturn(null);
            when(resource.getQueueItemId()).thenReturn(0L);
            when(resource.getQueueItemProject()).thenReturn(null);
            when(resource.getTask()).thenReturn(null);
            when(resource.getReservedBy()).thenReturn(null);
            when(resource.getReservedByEmail()).thenReturn(null);
        }

        return resource;
    }
}
