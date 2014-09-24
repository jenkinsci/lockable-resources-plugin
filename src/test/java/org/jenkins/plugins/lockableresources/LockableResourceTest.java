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


import hudson.model.AbstractBuild;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author aki
 */
public class LockableResourceTest {
	
	LockableResource instance;
	
	public LockableResourceTest() {
	}
	
	@BeforeClass
	public static void setUpClass() {
	}
	
	@AfterClass
	public static void tearDownClass() {
	}
	
	@Before
	public void setUp() {
		this.instance = new LockableResource("r1", "d1", "l1 l2", "");
	}
	
	@After
	public void tearDown() {
	}

	/**
	 * Test of getName method, of class LockableResource.
	 */
	@Test
	public void testGetName() {
		System.out.println("getName");
		
		String expResult = "r1";
		String result = instance.getName();
		assertEquals(expResult, result);
	}

	/**
	 * Test of getDescription method, of class LockableResource.
	 */
	@Test
	public void testGetDescription() {
		System.out.println("getDescription");
		String expResult = "d1";
		String result = instance.getDescription();
		assertEquals(expResult, result);
	}

	/**
	 * Test of getLabels method, of class LockableResource.
	 */
	 
	@Test
	public void testGetLabels() {
		System.out.println("getLabels");
		String expResult = "l1 l2";
		String result = instance.getLabels();
		assertEquals(expResult, result);
	}

	/**
	 * Test of getReservedBy method, of class LockableResource.
	 */
	@Test
	public void testGetReservedBy() {
		System.out.println("getReservedBy");
		String expResult = null;
		String result = instance.getReservedBy();
		assertEquals(expResult, result);
	}

	/**
	 * Test of isReserved method, of class LockableResource.
	 */
	@Test
	public void testIsReserved() {
		System.out.println("isReserved");
		boolean expResult = false;
		boolean result = instance.isReserved();
		assertEquals(expResult, result);
	}

	/**
	 * Test of isQueued method, of class LockableResource.
	 */
	@Test
	public void testIsQueued_0args() {
		System.out.println("isQueued");
		boolean expResult = false;
		boolean result = instance.isQueued();
		assertEquals(expResult, result);
	}

	/**
	 * Test of isQueued method, of class LockableResource.
	 */
	@Test
	public void testIsQueued_int() {
		System.out.println("isQueued");
		int taskId = 0;
		boolean expResult = false;
		boolean result = instance.isQueued(taskId);
		assertEquals(expResult, result);
	}

	/**
	 * Test of isQueuedByTask method, of class LockableResource.
	 */
	@Test
	public void testIsQueuedByTask() {
		System.out.println("isQueuedByTask");
		int taskId = 1;
		boolean expResult = false;
		boolean result = instance.isQueuedByTask(taskId);
		assertEquals(expResult, result);
	}

	/**
	 * Test of unqueue method, of class LockableResource.
	 */
	@Test
	public void testUnqueue() {
		System.out.println("unqueue");
		instance.unqueue();
	}

	/**
	 * Test of isLocked method, of class LockableResource.
	 */
	@Test
	public void testIsLocked() {
		System.out.println("isLocked");
		boolean expResult = false;
		boolean result = instance.isLocked();
		assertEquals(expResult, result);
	}

	/**
	 * Test of getBuild method, of class LockableResource.
	 */
	@Test
	public void testGetBuild() {
		System.out.println("getBuild");
		AbstractBuild expResult = null;
		AbstractBuild result = instance.getBuild();
		assertEquals(expResult, result);
	}

	/**
	 * Test of setBuild method, of class LockableResource.
	 */
	@Test
	public void testSetBuild() {
		System.out.println("setBuild");
		AbstractBuild lockedBy = null;
		instance.setBuild(lockedBy)	;
	}

	/**
	 * Test of getQueueItemId method, of class LockableResource.
	 */
	@Test
	public void testGetQueueItemId() {
		System.out.println("getQueueItemId");
		int expResult = 0;
		int result = instance.getQueueItemId();
		assertEquals(expResult, result);
	}

	/**
	 * Test of setQueueItemId method, of class LockableResource.
	 */
	@Test
	public void testSetQueueItemId() {
		System.out.println("setQueueItemId");
		int queueItemId = 0;
		instance.setQueueItemId(queueItemId);
	}

	/**
	 * Test of getQueueItemProject method, of class LockableResource.
	 */
	@Test
	public void testGetQueueItemProject() {
		System.out.println("getQueueItemProject");
		String expResult = null;
		String result = instance.getQueueItemProject();
		assertEquals(expResult, result);
	}

	/**
	 * Test of setQueueItemProject method, of class LockableResource.
	 */
	@Test
	public void testSetQueueItemProject() {
		System.out.println("setQueueItemProject");
		String queueItemProject = "";
		instance.setQueueItemProject(queueItemProject);
	}

	/**
	 * Test of setReservedBy method, of class LockableResource.
	 */
	@Test
	public void testSetReservedBy() {
		System.out.println("setReservedBy");
		String userName = "";
		instance.setReservedBy(userName);
	}

	/**
	 * Test of unReserve method, of class LockableResource.
	 */
	@Test
	public void testUnReserve() {
		System.out.println("unReserve");
		instance.unReserve();
	}

	/**
	 * Test of reset method, of class LockableResource.
	 */
	@Test
	public void testReset() {
		System.out.println("reset");
		instance.reset();
	}

	/**
	 * Test of toString method, of class LockableResource.
	 */
	@Test
	public void testToString() {
		System.out.println("toString");
		String expResult = "r1";
		String result = instance.toString();
		assertEquals(expResult, result);
	}


	/**
	 * Test of equals method, of class LockableResource.
	 */
	@Test
	public void testEquals() {
		System.out.println("equals");
		Object obj = null;
		boolean expResult = false;
		boolean result = instance.equals(obj);
		assertEquals(expResult, result);
	}

	/**
	 * Test of hashCode method, of class LockableResource.
	 */
	@Test
	public void testHashCode() {
		System.out.println("hashCode");
		int expResult = 0;
		int result = instance.hashCode();
	}
	
}
