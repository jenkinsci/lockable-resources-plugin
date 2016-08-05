package org.jenkins.plugins.lockableresources.queue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

@RunWith(PowerMockRunner.class)
@PrepareForTest({Utils.class, Job.class, LockableResourcesManager.class})
public class LockableResourcesQueueTaskDispatcherTest {

	@Test
	public void cause_of_blockage_is_null_when_resources_is_null() {
		// Given
		Queue.Item item = mock(Queue.Item.class);
		Job<?, ?> project = mock(Job.class);
		LockableResourcesStruct resources = null;

		// When
		CauseOfBlockage causeOfBlockage = LockableResourcesQueueTaskDispatcher.getCauseOfBlockage(item, project, resources);

		// Then
		assertThat(causeOfBlockage).isNull();
	}

	@Test
	public void cause_of_blockage_is_null_when_resource_names_and_label_are_empty() {
		// Given
		Queue.Item item = mock(Queue.Item.class);
		Job<?, ?> project = mock(Job.class);

		LockableResourcesStruct resources = mock(LockableResourcesStruct.class);

		resources.resourceNames = Collections.emptyList();
		resources.label = "";

		// When
		CauseOfBlockage causeOfBlockage = LockableResourcesQueueTaskDispatcher.getCauseOfBlockage(item, project, resources);

		// Then
		assertThat(causeOfBlockage).isNull();
	}

	@Test
	public void should_parse_required_number_even_if_number_format_exception_is_thrown() {
		// Given
		int resourceNumber = 0;

		Long itemId = 1L;
		Queue.Item item = mock(Queue.Item.class);

		when(item.getId()).thenReturn(itemId);

		String projectName = "full-name";
		Job<?, ?> project = PowerMockito.mock(Job.class);

		when(project.getFullName()).thenReturn(projectName);

		LockableResourcesStruct resources = mock(LockableResourcesStruct.class);

		resources.label = "label";
		resources.resourceNames = Lists.newArrayList("resource-name");
		resources.requiredNumber = "NOT_A_NUMBER";

		Map<String, Object> params = Maps.newHashMap();
		params.put("key", "value");

		mockStatic(Utils.class);
		when(Utils.getParams(item)).thenReturn(params);

		LockableResourcesManager manager = mock(LockableResourcesManager.class);
		mockStatic(LockableResourcesManager.class);

		when(LockableResourcesManager.get()).thenReturn(manager);

		List<LockableResource> reservedResources = Lists.newArrayList();

		when(manager.queue(resources, itemId, projectName, resourceNumber, params)).thenReturn(reservedResources);

		// When
		CauseOfBlockage causeOfBlockage = LockableResourcesQueueTaskDispatcher.getCauseOfBlockage(item, project, resources);

		// Then
		verify(manager).queue(resources, itemId, projectName, resourceNumber, params);

		assertThat(causeOfBlockage).isNull();
	}

	@Test
	public void cause_of_blockage_is_null_when_resources_have_been_reserved() {
		// Given
		int resourceNumber = 0;

		Long itemId = 1L;
		Queue.Item item = mock(Queue.Item.class);

		when(item.getId()).thenReturn(itemId);

		String projectName = "full-name";
		Job<?, ?> project = PowerMockito.mock(Job.class);

		when(project.getFullName()).thenReturn(projectName);

		LockableResourcesStruct resources = mock(LockableResourcesStruct.class);

		resources.label = "label";
		resources.resourceNames = Lists.newArrayList("resource-name");
		resources.requiredNumber = String.valueOf(resourceNumber);

		Map<String, Object> params = Maps.newHashMap();
		params.put("key", "value");

		mockStatic(Utils.class);
		when(Utils.getParams(item)).thenReturn(params);

		LockableResourcesManager manager = mock(LockableResourcesManager.class);
		mockStatic(LockableResourcesManager.class);

		when(LockableResourcesManager.get()).thenReturn(manager);

		List<LockableResource> reservedResources = Lists.newArrayList(new LockableResource("resource"));

		when(manager.queue(resources, itemId, projectName, resourceNumber, params)).thenReturn(reservedResources);

		// When
		CauseOfBlockage causeOfBlockage = LockableResourcesQueueTaskDispatcher.getCauseOfBlockage(item, project, resources);

		// Then
		verify(manager).queue(resources, itemId, projectName, resourceNumber, params);

		assertThat(causeOfBlockage).isNull();
	}

	@Test
	public void should_get_cause_of_blockage_with_label_when_resources_have_not_been_reserved() {
		// Given
		int resourceNumber = 0;

		Long itemId = 1L;
		Queue.Item item = mock(Queue.Item.class);

		when(item.getId()).thenReturn(itemId);

		String projectName = "full-name";
		Job<?, ?> project = PowerMockito.mock(Job.class);

		when(project.getFullName()).thenReturn(projectName);

		LockableResourcesStruct resources = mock(LockableResourcesStruct.class);

		String label = "label";

		resources.label = label;
		resources.resourceNames = Lists.newArrayList("resource-name");
		resources.requiredNumber = String.valueOf(resourceNumber);

		Map<String, Object> params = Maps.newHashMap();
		params.put("key", "value");

		mockStatic(Utils.class);
		when(Utils.getParams(item)).thenReturn(params);

		LockableResourcesManager manager = mock(LockableResourcesManager.class);
		mockStatic(LockableResourcesManager.class);

		when(LockableResourcesManager.get()).thenReturn(manager);
		when(manager.queue(resources, itemId, projectName, resourceNumber, params)).thenReturn(null);

		// When
		CauseOfBlockage causeOfBlockage = LockableResourcesQueueTaskDispatcher.getCauseOfBlockage(item, project, resources);

		// Then
		verify(manager).queue(resources, itemId, projectName, resourceNumber, params);

		assertThat(causeOfBlockage).isNotNull();
		assertThat(causeOfBlockage.getShortDescription()).isEqualTo("Waiting for resources with label " + label);
	}

	@Test
	public void should_get_cause_of_blockage_with_resource_names_when_resources_have_not_been_reserved() {
		// Given
		Long itemId = 1L;
		Queue.Item item = mock(Queue.Item.class);

		when(item.getId()).thenReturn(itemId);

		String projectName = "full-name";
		Job<?, ?> project = PowerMockito.mock(Job.class);

		when(project.getFullName()).thenReturn(projectName);

		LockableResourcesStruct resources = mock(LockableResourcesStruct.class);

		String label = "";
		ArrayList<String> resourceNames = Lists.newArrayList("resource-name");
		String resourceNumber = "";

		resources.label = label;
		resources.resourceNames = resourceNames;
		resources.requiredNumber = resourceNumber;

		Map<String, Object> params = Maps.newHashMap();
		params.put("key", "value");

		mockStatic(Utils.class);
		when(Utils.getParams(item)).thenReturn(params);

		LockableResourcesManager manager = mock(LockableResourcesManager.class);
		mockStatic(LockableResourcesManager.class);

		when(LockableResourcesManager.get()).thenReturn(manager);
		when(manager.queue(resources, itemId, projectName, params)).thenReturn(null);

		// When
		CauseOfBlockage causeOfBlockage = LockableResourcesQueueTaskDispatcher.getCauseOfBlockage(item, project, resources);

		// Then
		verify(manager).queue(resources, itemId, projectName, params);

		assertThat(causeOfBlockage).isNotNull();
		assertThat(causeOfBlockage.getShortDescription()).isEqualTo("Waiting for resources " + resourceNames);
	}

}