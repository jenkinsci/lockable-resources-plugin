package org.jenkins.plugins.lockableresources.util;

public class Constants {
  /// Enable mirror nodes to lockable-resources
  public static final String SYSTEM_PROPERTY_ENABLE_NODE_MIRROR =
      "org.jenkins.plugins.lockableresources.ENABLE_NODE_MIRROR";
  /// Disable saving lockable resources states, properties ... into local file system.
  /// This option makes the plugin much faster (everything is in cache) but
  /// **Keep in mind, that you will lost all your manual changed properties**
  /// The best way is to use it with JCaC plugin.
  public static final String SYSTEM_PROPERTY_DISABLE_SAVE =
      "org.jenkins.plugins.lockableresources.DISABLE_SAVE";
  /// Enable to print lock causes. Keep in mind, that the log output may grove depends on count of
  // resources.
  public static final String SYSTEM_PROPERTY_PRINT_LOCK_CAUSES =
      "org.jenkins.plugins.lockableresources.PRINT_LOCK_CAUSES";
}
