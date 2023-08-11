package org.jenkins.plugins.lockableresources;

import org.jenkins.plugins.lockableresources.util.Constants;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.recipes.WithTimeout;

public class PressureTest20 extends PressureTestHelpers {

  /**
   * Pressure test to lock resources via labels, resource name, ephemeral ... It simulates big
   * system with many chaotic locks. Hopefully it runs always good, because any analysis here will
   * be very hard.
   */
  @Test
  @WithTimeout(300)
  public void pressureEnableSave() throws Exception {
    pressure(20);
  }
  @Test
  @WithTimeout(300)
  public void pressureDisableSave() throws Exception {
    System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
    pressure(20);
  }

}
