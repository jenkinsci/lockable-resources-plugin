package org.jenkins.plugins.lockableresources.util;

public class Constants {
    /// Enable mirror nodes to lockable-resources
    public static final String SYSTEM_PROPERTY_ENABLE_NODE_MIRROR =
            "org.jenkins.plugins.lockableresources.ENABLE_NODE_MIRROR";
    /// Disable saving lockable resources states, properties ... into local file system.
    /// This option makes the plugin much faster (everything is in cache) but
    /// **Keep in mind, that you will lost all your manual changed properties**
    /// The best way is to use it with JCaC plugin.
    public static final String SYSTEM_PROPERTY_DISABLE_SAVE = "org.jenkins.plugins.lockableresources.DISABLE_SAVE";
    /// Enable to print lock causes. Keep in mind, that the log output may grove depends on count of
    /// blocked resources.
    public static final String SYSTEM_PROPERTY_PRINT_BLOCKED_RESOURCE =
            "org.jenkins.plugins.lockableresources.PRINT_BLOCKED_RESOURCE";
    public static final String SYSTEM_PROPERTY_PRINT_QUEUE_INFO =
            "org.jenkins.plugins.lockableresources.PRINT_QUEUE_INFO";
    /// Enable asynchronous save to reduce syncResources lock hold time.
    public static final String SYSTEM_PROPERTY_ASYNC_SAVE = "org.jenkins.plugins.lockableresources.ASYNC_SAVE";
    /// Coalesce window (ms) for async saves — rapid state changes within this window are batched.
    public static final String SYSTEM_PROPERTY_SAVE_COALESCE_MS =
            "org.jenkins.plugins.lockableresources.SAVE_COALESCE_MS";
    /// TTL (ms) for Groovy script evaluation result cache per resource.
    public static final String SYSTEM_PROPERTY_SCRIPT_CACHE_TTL_MS =
            "org.jenkins.plugins.lockableresources.SCRIPT_CACHE_TTL_MS";
    /// TTL (ms) for label expression evaluation result cache per resource.
    public static final String SYSTEM_PROPERTY_LABEL_CACHE_TTL_MS =
            "org.jenkins.plugins.lockableresources.LABEL_CACHE_TTL_MS";
}
