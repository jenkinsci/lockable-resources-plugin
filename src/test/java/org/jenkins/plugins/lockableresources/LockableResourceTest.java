package org.jenkins.plugins.lockableresources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Date;
import org.junit.Test;

public class LockableResourceTest {

  LockableResource instance = new LockableResource("r1");

  // Not sure how useful this is...
  @Test
  public void testGetters() {
    assertEquals("r1", instance.getName());
    assertEquals("", instance.getDescription());
    assertEquals("", instance.getLabels());
    assertNull(instance.getReservedBy());
    assertNull(instance.getReservedTimestamp());
    assertFalse(instance.isReserved());
    assertFalse(instance.isQueued());
    assertFalse(instance.isQueued(0));
    assertFalse(instance.isQueuedByTask(1));
    assertFalse(instance.isLocked());
    assertNull(instance.getBuild());
    assertEquals(0, instance.getQueueItemId());
    assertNull(instance.getQueueItemProject());
  }

  @Test
  public void testUnqueue() {
    instance.unqueue();
  }

  @Test
  public void testSetBuild() {
    instance.setBuild(null);
  }

  @Test
  public void testSetReservedBy() {
    instance.setReservedBy("");
  }

  @Test
  public void testReservedTimestamp() {
    instance.setReservedTimestamp(null);
    assertNull(instance.getReservedTimestamp());

    final Date date = new Date();
    instance.setReservedTimestamp(date);
    assertEquals(date, instance.getReservedTimestamp());
  }

  @Test
  public void testReserve() {
    instance.reserve("testUser1");
    assertEquals("testUser1", instance.getReservedBy());
    assertNotNull(instance.getReservedTimestamp());
  }

  @Test
  public void testUnReserve() {
    instance.unReserve();
    assertNull(instance.getReservedBy());
    assertNull(instance.getReservedTimestamp());
  }

  @Test
  public void testReset() {
    instance.reset();
  }

  @Test
  public void testToString() {
    assertEquals("r1", instance.toString());
  }

  @Test
  public void testEquals() {
    assertFalse(instance.equals(null));
  }
}
