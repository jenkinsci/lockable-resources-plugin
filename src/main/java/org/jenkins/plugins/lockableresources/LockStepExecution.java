package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;

public class LockStepExecution extends AbstractStepExecutionImpl implements Serializable {

    private static final long serialVersionUID = 1391734561272059623L;

    private static final Logger LOGGER = Logger.getLogger(LockStepExecution.class.getName());

    private final LockStep step;

    public LockStepExecution(LockStep step, StepContext context) {
        super(context);
        this.step = step;
    }

    @Override
    public boolean start() throws Exception {
        // normally it might raise an exception, but we check it in the function .validate()
        // therefore we can skip the try-catch here.
        ResourceSelectStrategy resourceSelectStrategy =
                ResourceSelectStrategy.valueOf(step.resourceSelectStrategy.toUpperCase(Locale.ENGLISH));

        PrintStream logger = getContext().get(TaskListener.class).getLogger();

        Run<?, ?> run = getContext().get(Run.class);

        List<LockableResourcesStruct> resourceHolderList = new ArrayList<>();

        List<LockableResource> available;
        LinkedHashMap<String, List<LockableResourceProperty>> lockedResources = new LinkedHashMap<>();
        LockableResourcesManager lrm = LockableResourcesManager.get();
        synchronized (LockableResourcesManager.syncResources) {
            step.validate(lrm.isAllowEmptyOrNullValues());

            LockableResourcesManager.printLogs("Trying to acquire lock on [" + step + "]", Level.FINE, LOGGER, logger);

            getContext().get(FlowNode.class).addAction(new PauseAction("Lock"));

            if (!lrm.isAllowEmptyOrNullValues() || acquireLock()) {
                List<String> resourceNames = new ArrayList<>();
                for (LockStepResource resource : step.getResources()) {
                    List<String> resources = new ArrayList<>();
                    if (resource.resource != null) {
                        if (lrm.createResource(resource.resource)) {
                            LockableResourcesManager.printLogs(
                                    "Resource [" + resource.resource + "] did not exist. Created.",
                                    Level.FINE,
                                    LOGGER,
                                    logger);
                        }
                        resources.add(resource.resource);
                        resourceNames.addAll(resources);
                    } else {
                        resourceNames.add("N/A");
                    }
                    resourceHolderList.add(new LockableResourcesStruct(resources, resource.label, resource.quantity));
                }
                LockedResourcesBuildAction.addLog(run, resourceNames, "try", step.toString());
                // determine if there are enough resources available to proceed
                available = lrm.getAvailableResources(resourceHolderList, logger, resourceSelectStrategy);
                if (available == null || available.isEmpty()) {
                    LOGGER.fine("No available resources: " + available);
                    onLockFailed(logger, resourceHolderList);
                    return false;
                }

                if (!lrm.lock(available, run)) {
                    // this here is very defensive code, and you will probably never hit it. (hopefully)
                    LOGGER.warning("Internal program error: Can not lock resources: " + available);
                    onLockFailed(logger, resourceHolderList);
                    return true;
                }

                // since LockableResource contains transient variables, they cannot be correctly serialized
                // hence we use their unique resource names and properties
                for (LockableResource resource : available) {
                    lockedResources.put(resource.getName(), resource.getProperties());
                }
            }

            LockStepExecution.proceed(lockedResources, getContext(), step.toString(), step.variable);
        }

        return false;
    }

    // ---------------------------------------------------------------------------
    /**
     * Checks if a lock can be acquired based on the step's properties: label, resource, and extra.
     * To acquire a lock, at least one of these properties must be non-null and non-empty.
     */
    private boolean acquireLock() {
        if (step.label != null) {
            return true;
        }
        if (step.resource != null) {
            return true;
        }
        if (step.extra != null && !step.extra.isEmpty()) {
            return true;
        }
        LOGGER.warning("No lock will be acquired. Either the label, resource or extra is null or empty.");
        LOGGER.warning("Step: " + step);
        LOGGER.warning("Label: " + step.label);
        LOGGER.warning("Resource: " + step.resource);
        LOGGER.warning("Extra: " + step.extra);
        return false;
    }

    // ---------------------------------------------------------------------------
    /**
     * Executed when the lock() function fails. No available resources, or we failed to lock available
     * resources if the resource is known, we could output the active/blocking job/build
     */
    private void onLockFailed(PrintStream logger, List<LockableResourcesStruct> resourceHolderList) {

        if (step.skipIfLocked) {
            this.printBlockCause(logger, resourceHolderList);
            LockableResourcesManager.printLogs(
                    "[" + step + "] is not free, skipping execution ...", Level.FINE, LOGGER, logger);
            getContext().onSuccess(null);
        } else {
            this.printBlockCause(logger, resourceHolderList);
            LockableResourcesManager.printLogs(
                    "[" + step + "] is not free, waiting for execution ...", Level.FINE, LOGGER, logger);
            LockableResourcesManager lrm = LockableResourcesManager.get();
            lrm.queueContext(
                    getContext(),
                    resourceHolderList,
                    step.toString(),
                    step.variable,
                    step.inversePrecedence,
                    step.priority);
        }
    }

    private void printBlockCause(PrintStream logger, List<LockableResourcesStruct> resourceHolderList) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        LockableResource resource = this.step.resource != null ? lrm.fromName(this.step.resource) : null;

        if (resource != null) {
            final String logMessage = resource.getLockCauseDetail();
            if (logMessage != null && !logMessage.isEmpty())
                LockableResourcesManager.printLogs(logMessage, Level.FINE, LOGGER, logger);
        } else {
            // looks like ordered by label
            lrm.getAvailableResources(resourceHolderList, logger, null);
        }
    }

    // ---------------------------------------------------------------------------
    @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION", justification = "not sure which exceptions might be catch.")
    public static void proceed(
            final LinkedHashMap<String, List<LockableResourceProperty>> lockedResources,
            StepContext context,
            String resourceDescription,
            final String variable) {
        Run<?, ?> build;
        FlowNode node;
        PrintStream logger;
        try {
            build = context.get(Run.class);
            node = context.get(FlowNode.class);
            logger = context.get(TaskListener.class).getLogger();
            LockableResourcesManager.printLogs(
                    "Lock acquired on [" + resourceDescription + "]", Level.FINE, LOGGER, logger);
        } catch (Exception e) {
            context.onFailure(e);
            return;
        }

        try {
            List<String> resourceNames = new ArrayList<>(lockedResources.keySet());
            final String resourceNamesAsString = String.join(",", lockedResources.keySet());
            LockedResourcesBuildAction.addLog(build, resourceNames, "acquired", resourceDescription);
            PauseAction.endCurrentPause(node);
            BodyInvoker bodyInvoker =
                    context.newBodyInvoker().withCallback(new Callback(resourceNames, resourceDescription));
            if (variable != null && !variable.isEmpty()) {
                // set the variable for the duration of the block
                bodyInvoker.withContext(
                        EnvironmentExpander.merge(context.get(EnvironmentExpander.class), new EnvironmentExpander() {
                            private static final long serialVersionUID = -3431466225193397896L;

                            @Override
                            public void expand(@NonNull EnvVars env) {
                                final LinkedHashMap<String, String> variables = new LinkedHashMap<>();
                                variables.put(variable, resourceNamesAsString);
                                int index = 0;
                                for (Entry<String, List<LockableResourceProperty>> lockResourceEntry :
                                        lockedResources.entrySet()) {
                                    String lockEnvName = variable + index;
                                    variables.put(lockEnvName, lockResourceEntry.getKey());
                                    for (LockableResourceProperty lockProperty : lockResourceEntry.getValue()) {
                                        String propEnvName = lockEnvName + "_" + lockProperty.getName();
                                        variables.put(propEnvName, lockProperty.getValue());
                                    }
                                    ++index;
                                }
                                LOGGER.finest("Setting "
                                        + variables.entrySet().stream()
                                                .map(e -> e.getKey() + "=" + e.getValue())
                                                .collect(Collectors.joining(", "))
                                        + " for the duration of the block");
                                env.overrideAll(variables);
                            }
                        }));
            }
            bodyInvoker.start();
        } catch (IOException | InterruptedException e) {
            LOGGER.warning("proceed done with failure " + resourceDescription);
            throw new RuntimeException(e);
        }
    }

    private static final class Callback extends BodyExecutionCallback.TailCall {

        private static final long serialVersionUID = -2024890670461847666L;
        private final List<String> resourceNames;
        private final String resourceDescription;

        Callback(List<String> resourceNames, String resourceDescription) {
            this.resourceNames = resourceNames;
            this.resourceDescription = resourceDescription;
        }

        @Override
        protected void finished(StepContext context) throws Exception {
            Run<?, ?> build = context.get(Run.class);
            LockedResourcesBuildAction.addLog(build, this.resourceNames, "released", this.resourceDescription);
            LockableResourcesManager.get().unlockNames(this.resourceNames, build);
            LockableResourcesManager.printLogs(
                    "Lock released on resource [" + this.resourceDescription + "]",
                    Level.FINE,
                    LOGGER,
                    context.get(TaskListener.class).getLogger());
        }
    }

    @Override
    public void stop(@NonNull Throwable cause) {
        boolean cleaned = LockableResourcesManager.get().unqueueContext(getContext());
        if (!cleaned) {
            LOGGER.log(
                    Level.WARNING,
                    "Cannot remove context from lockable resource waiting list. The context is not in the waiting list.");
        }
        getContext().onFailure(cause);
    }
}
