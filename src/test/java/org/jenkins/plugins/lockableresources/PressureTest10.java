package org.jenkins.plugins.lockableresources;

import org.jenkins.plugins.lockableresources.util.Constants;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.recipes.WithTimeout;

public class PressureTest10 extends PressureTestHelpers {

  /**
   * Pressure test to lock resources via labels, resource name, ephemeral ... It simulates big
   * system with many chaotic locks. Hopefully it runs always good, because any analysis here will
   * be very hard.
   */
  @Test
  @WithTimeout(120)
  public void pressureEnableSave() throws Exception {
    pressure(10);
  }
  @Test
  @WithTimeout(120)
  public void pressureDisableSave() throws Exception {
    System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    pressure(10);
  }

}
