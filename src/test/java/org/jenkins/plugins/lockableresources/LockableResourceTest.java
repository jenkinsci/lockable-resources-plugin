package org.jenkins.plugins.lockableresources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LockableResourceTest {

  LockableResource instance = new LockableResource("r1");

  // Not sure how useful this is...
  @Test
  public void testGetters() {
    assertEquals("r1", instance.getName());
    assertEquals("", instance.getDescription());
    assertEquals("", instance.getLabels());
    assertEquals("", instance.getNote());
    assertNull(instance.getReservedBy());
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
  public void testNote() {
    final LockableResource resource = new LockableResource("Name 1");

    assertEquals("", resource.getNote());

    resource.setNote("Note 1");
    assertEquals("Note 1", resource.getNote());

    resource.setNote("Note B");
    assertEquals("Note B", resource.getNote());

    resource.setNote("");
    assertEquals("", resource.getNote());
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
  public void testUnReserve() {
    instance.unReserve();
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
