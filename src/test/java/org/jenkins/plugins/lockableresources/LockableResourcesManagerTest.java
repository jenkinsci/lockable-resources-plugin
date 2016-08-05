package org.jenkins.plugins.lockableresources;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Lists;

import hudson.model.Run;

import static org.assertj.core.api.Assertions.assertThat;
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
		Map<String, Object> params = Collections.emptyMap();

		LockableResource resource1 = mockLockableResource("resource1", "r1");
		LockableResource resource2 = mockLockableResource("resource2", "r2");
		LockableResource resource3 = mockLockableResource("resource3", "r3");

		List<LockableResource> resources = Lists.newArrayList();

		resources.add(resource1);
		resources.add(resource2);
		resources.add(resource3);

		setField(lockableResourcesManager, "resources", resources, List.class);

		String queueItemProject = "other-project";

		when(resource1.getQueueItemProject()).thenReturn(queueItemProject);

		when(resource2.getQueueItemProject()).thenReturn(fullName);
		when(resource2.isValidLabel(label, params)).thenReturn(true);

		when(resource3.getQueueItemProject()).thenReturn(fullName);
		when(resource3.isValidLabel(label, params)).thenReturn(false);

		// When
		List<LockableResource> matching = lockableResourcesManager.getResourcesFromProject(fullName, label, params);

		// Then
		verify(resource1).getQueueItemProject();
		verify(resource1, never()).isValidLabel(label, params);

		verify(resource2).getQueueItemProject();
		verify(resource2).isValidLabel(label, params);

		assertThat(matching).containsExactly(resource2);
	}

	@SuppressWarnings("unchecked")
	@Test
	public void should_get_resources_from_build() throws Exception {
		// Given
		Run build = mock(Run.class);

		LockableResource resource1 = mock(LockableResource.class);
		LockableResource resource2 = mock(LockableResource.class);

		List<LockableResource> resources = Lists.newArrayList();

		resources.add(resource1);
		resources.add(resource2);

		setField(lockableResourcesManager, "resources", resources, List.class);

		when(resource1.getBuild()).thenReturn(build);

		// When
		List<LockableResource> matching = lockableResourcesManager.getResourcesFromBuild(build);

		// Then
		assertThat(matching).containsExactly(resource1);
	}

	private LockableResource mockLockableResource(String name, String labels) throws Exception {
		LockableResource resource = mock(LockableResource.class);

		setField(resource, "name", name, String.class);
		resource.setLabels(labels);

		return resource;
	}

}