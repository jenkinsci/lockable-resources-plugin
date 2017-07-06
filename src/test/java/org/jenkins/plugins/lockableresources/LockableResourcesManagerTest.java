/*
 * The MIT License
 *
 * Copyright 2014 Aki Asikainen.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkins.plugins.lockableresources;

import static org.mockito.Mockito.*;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.junit.*;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Marcus Antonsson
 */
public class LockableResourcesManagerTest {

	LockableResourcesManager resourceManager;

	public LockableResourcesManagerTest() {	}

	@Before
	public void setUp() {
		resourceManager = mock(LockableResourcesManager.class);
	}

	@Test
	public void checkAvailabilityShouldNotReturnMoreThanRequiredResources() {
		// Configure partial mock for SUT and mock underlying classes
		when(resourceManager.checkResourcesAvailability(any(LockableResourcesStruct.class), isNull(PrintStream.class), any(ArrayList.class)))
				.thenCallRealMethod();

		ArrayList<LockableResource> candidateList = new ArrayList<LockableResource>();

		// In order to create resources named r0, r1, ..., r(n)
		for (int i = 0; i < 5; ++i) {
			LockableResource l = mock(LockableResource.class);
			when(l.getName()).thenReturn("r" + i);
			candidateList.add(l);
		}

		when(resourceManager.getResourcesWithLabel(any(String.class), any(Map.class)))
				.thenReturn(candidateList);

		LockableResourcesStruct resourceStructMock = mock(LockableResourcesStruct.class);
		resourceStructMock.label = "LABEL";
		resourceStructMock.requiredNumber = "2";

		// Size of soonToBeUnlocked need to be lower than requiredNumber
		ArrayList<String> soonToBeUnlockedList = new ArrayList<>();
		soonToBeUnlockedList.add("r1");
		soonToBeUnlockedList.add("r2");
		soonToBeUnlockedList.add("r4");

		List<LockableResource> lockableResources = resourceManager.checkResourcesAvailability(resourceStructMock, null, soonToBeUnlockedList);

		// Make sure that the manager can figure out we need 2 resources even though there are more on the way
		Assert.assertNotNull(lockableResources);
		Assert.assertEquals(2, lockableResources.size());
	}

	@Test
	public void checkAvailabilityShouldReturnNullResources() {
		// Configure partial mock for SUT and mock underlying classes
		when(resourceManager.checkResourcesAvailability(any(LockableResourcesStruct.class), isNull(PrintStream.class), any(ArrayList.class)))
				.thenCallRealMethod();

		ArrayList<LockableResource> candidateList = new ArrayList<LockableResource>();

		// In order to create resources named r0, r1, ..., r(n)
		for (int i = 0; i < 5; ++i) {
			LockableResource l = mock(LockableResource.class);
			when(l.getName()).thenReturn("r" + i);
			candidateList.add(l);
		}

		when(resourceManager.getResourcesWithLabel(any(String.class), any(Map.class)))
				.thenReturn(candidateList);

		LockableResourcesStruct resourceStructMock = mock(LockableResourcesStruct.class);
		resourceStructMock.label = "LABEL";
		resourceStructMock.requiredNumber = "10";

		// Size of soonToBeUnlocked need to be lower than requiredNumber
		ArrayList<String> soonToBeUnlockedList = new ArrayList<>();
		soonToBeUnlockedList.add("r1");
		soonToBeUnlockedList.add("r2");
		soonToBeUnlockedList.add("r4");

		List<LockableResource> lockableResources = resourceManager.checkResourcesAvailability(resourceStructMock, null, soonToBeUnlockedList);

		// There are not enough resources to meet requirement so return value should be null
		Assert.assertNull(lockableResources);
	}
}