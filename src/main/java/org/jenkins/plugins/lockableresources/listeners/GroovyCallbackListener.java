/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.listeners;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import groovy.lang.Binding;
import hudson.Extension;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;

/**
 * Built-in {@link ResourceEventListener} that evaluates the global Groovy callback script
 * configured in the Lockable Resources Manager.
 *
 * <p>The callback can run synchronously or asynchronously (default), controlled by the global
 * configuration. A configurable timeout prevents runaway scripts from blocking operations.
 */
@Extension
public class GroovyCallbackListener extends ResourceEventListener {

    private static final Logger LOGGER = Logger.getLogger(GroovyCallbackListener.class.getName());

    private static final ExecutorService CALLBACK_EXECUTOR =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r);
                t.setName("lockable-resources-event-callback");
                t.setDaemon(true);
                return t;
            });

    @Override
    public void onEvent(
            @NonNull ResourceEvent event,
            @NonNull List<LockableResource> resources,
            @Nullable Run<?, ?> build,
            @Nullable String userName) {

        LockableResourcesManager lrm = LockableResourcesManager.get();
        SecureGroovyScript script = lrm.getOnResourceEventScript();
        if (script == null || script.getScript().trim().isEmpty()) {
            return;
        }

        List<ResourceInfo> resourceInfos = new ArrayList<>();
        for (LockableResource r : resources) {
            resourceInfos.add(new ResourceInfo(r));
        }

        String eventName = event.name();
        String buildName = build != null ? build.getFullDisplayName() : null;
        boolean async = lrm.isEventCallbackAsync();
        int timeout = lrm.getEventCallbackTimeoutSec();
        boolean ignoreExceptions = lrm.isEventCallbackIgnoreExceptions();

        if (async) {
            Future<?> future = CALLBACK_EXECUTOR.submit(() -> {
                executeCallback(script, resourceInfos, eventName, buildName, userName, ignoreExceptions);
            });
            // schedule timeout enforcement
            CALLBACK_EXECUTOR.submit(() -> {
                try {
                    future.get(timeout, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    LOGGER.warning("Event callback timed out after " + timeout + "s for " + eventName);
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Error waiting for callback future", e);
                }
            });
        } else {
            executeCallback(script, resourceInfos, eventName, buildName, userName, ignoreExceptions);
        }
    }

    private static void executeCallback(
            SecureGroovyScript script,
            List<ResourceInfo> resourceInfos,
            String eventName,
            @Nullable String buildName,
            @Nullable String userName,
            boolean ignoreExceptions) {
        for (ResourceInfo info : resourceInfos) {
            try {
                Binding binding = new Binding();
                binding.setVariable("resource", info);
                binding.setVariable("event", eventName);
                binding.setVariable("userName", userName);
                binding.setVariable("buildName", buildName);

                Jenkins jenkins = Jenkins.get();
                script.evaluate(jenkins.getPluginManager().uberClassLoader, binding, null);
            } catch (Exception e) {
                if (ignoreExceptions) {
                    LOGGER.log(
                            Level.WARNING,
                            "Event callback script failed for " + eventName + " on " + info.getName(),
                            e);
                } else {
                    throw new RuntimeException(
                            "Event callback script failed for " + eventName + " on " + info.getName(), e);
                }
            }
        }
    }
}
