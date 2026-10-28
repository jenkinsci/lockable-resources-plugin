package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkins.plugins.lockableresources.remote.RemoteLockRouting;
import org.jenkins.plugins.lockableresources.remote.RemoteLockSession;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;

public class LockStepExecution extends AbstractStepExecutionImpl implements RemoteLockSession.Host {

    private static final long serialVersionUID = 1391734561272059623L;

    private static final Logger LOGGER = Logger.getLogger(LockStepExecution.class.getName());

    private final LockStep step;

    /** Non-null once a remote {@code lock()} flow has started; owns the acquire/poll/heartbeat/release machine. */
    private RemoteLockSession remoteSession;

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

        if (RemoteLockRouting.isRemoteRequest(step, lrm)) {
            remoteSession = new RemoteLockSession();
            return remoteSession.start(this);
        }
        step.validate(lrm.isAllowEmptyOrNullValues());

        synchronized (LockableResourcesManager.syncResources) {
            if (step.reentrant && tryReenter(run, logger)) {
                return false;
            }

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

                if (!lrm.lock(available, run, step.reason)) {
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
    // RemoteLockSession.Host - the remote acquire/poll/heartbeat/release machine lives in RemoteLockSession;
    // these are the thin step-side hooks it calls back into (expose the step context, run the lock body).

    @Override
    public StepContext context() {
        return getContext();
    }

    @Override
    public LockStep step() {
        return step;
    }

    @Override
    @SuppressFBWarnings(
            value = "REC_CATCH_EXCEPTION",
            justification = "Fail-closed: any failure while starting the body must fail the session.")
    public void runBody(
            String displayTarget,
            @edu.umd.cs.findbugs.annotations.CheckForNull Map<String, String> lockEnvVars,
            String lockId) {
        try {
            Run<?, ?> build = getContext().get(Run.class);
            FlowNode node = getContext().get(FlowNode.class);
            PrintStream logger = getContext().get(TaskListener.class).getLogger();
            LockableResourcesManager.printLogs(
                    "Remote lock acquired on ["
                            + step
                            + "] (serverId="
                            + remoteSession.getServerId()
                            + ", lockId="
                            + lockId
                            + ")",
                    Level.FINE,
                    LOGGER,
                    logger);
            LockedResourcesBuildAction.addLog(
                    build, Collections.singletonList(displayTarget), "acquired", step.toString());
            PauseAction.endCurrentPause(node);

            BodyInvoker bodyInvoker = getContext().newBodyInvoker().withCallback(new RemoteCallback(displayTarget));
            // The bridge metadata (X_SERVER_ID / X_LOCK_ID) does not depend on what the server returned:
            // gating it on a non-empty lockEnvVars dropped it whenever the response carried none.
            Map<String, String> envWithRemoteMetadata = withRemoteMetadata(
                    lockEnvVars == null ? Collections.emptyMap() : lockEnvVars, step.variable, remoteSession, lockId);
            if (!envWithRemoteMetadata.isEmpty()) {
                bodyInvoker.withContext(EnvironmentExpander.merge(
                        getContext().get(EnvironmentExpander.class), new EnvVarsExpander(envWithRemoteMetadata)));
            }
            bodyInvoker.start();
        } catch (Exception ex) {
            remoteSession.fail(this, ex);
        }
    }

    // ---------------------------------------------------------------------------
    /**
     * Runs the body right away when every requested resource is already held by an enclosing lock block of
     * this build. Nothing is locked here and nothing is released when the body ends; the enclosing block
     * still owns the resources. Returns false (lock as usual) on partial overlap or for label requests.
     */
    private boolean tryReenter(Run<?, ?> run, PrintStream logger) throws IOException, InterruptedException {
        List<String> names = new ArrayList<>();
        for (LockStepResource resource : step.getResources()) {
            if (resource.resource == null) {
                LockableResourcesManager.printLogs(
                        "reentrant is only supported for named resources, [" + step + "] is locked as usual",
                        Level.FINE,
                        LOGGER,
                        logger);
                return false;
            }
            names.add(resource.resource);
        }
        HeldResources held = getContext().get(HeldResources.class);
        if (names.isEmpty() || held == null || !held.names.containsAll(names)) {
            return false;
        }

        LockableResourcesManager lrm = LockableResourcesManager.get();
        LinkedHashMap<String, List<LockableResourceProperty>> heldResources = new LinkedHashMap<>();
        for (String name : names) {
            LockableResource resource = lrm.fromName(name);
            Run<?, ?> lockedBy = resource == null ? null : resource.getBuild();
            if (lockedBy == null || !lockedBy.getExternalizableId().equals(run.getExternalizableId())) {
                // the enclosing block lost the resource (e.g. unlocked in the UI), so really lock it
                return false;
            }
            heldResources.put(name, resource.getProperties());
        }

        LockableResourcesManager.printLogs(
                "Resource " + formatResourceNames(names)
                        + " is already held by an enclosing lock, entering without waiting",
                Level.FINE,
                LOGGER,
                logger);
        startBody(heldResources, getContext(), step.toString(), step.variable, false);
        return true;
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
            String waitMsg = "[" + step + "] is not free, waiting for execution ...";
            if (step.timeoutForAllocateResource > 0) {
                waitMsg += " (timeout: " + step.timeoutForAllocateResource + " "
                        + step.timeoutUnit.toLowerCase(java.util.Locale.ENGLISH) + ")";
            }
            LockableResourcesManager.printLogs(waitMsg, Level.FINE, LOGGER, logger);
            LockableResourcesManager lrm = LockableResourcesManager.get();
            lrm.queueContext(
                    getContext(),
                    resourceHolderList,
                    step.toString(),
                    step.variable,
                    step.inversePrecedence,
                    step.priority,
                    step.reason,
                    step.timeoutForAllocateResource,
                    step.timeoutUnit);
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

    private static final int MAX_LOGGED_RESOURCE_NAMES = 5;

    static String formatResourceNames(Collection<String> names) {
        List<String> shown = new ArrayList<>();
        for (String name : names) {
            if (shown.size() == MAX_LOGGED_RESOURCE_NAMES) {
                break;
            }
            shown.add(name);
        }
        int hidden = names.size() - shown.size();
        if (hidden > 0) {
            shown.add("+" + hidden + " more");
        }
        return shown.toString();
    }

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
            String message = "Lock acquired on [" + resourceDescription + "]";
            if (!lockedResources.isEmpty()) {
                message += ", resources: " + formatResourceNames(lockedResources.keySet());
            }
            LockableResourcesManager.printLogs(message, Level.FINE, LOGGER, logger);
        } catch (Exception e) {
            context.onFailure(e);
            return;
        }

        try {
            LockedResourcesBuildAction.addLog(
                    build, new ArrayList<>(lockedResources.keySet()), "acquired", resourceDescription);
            PauseAction.endCurrentPause(node);
            startBody(lockedResources, context, resourceDescription, variable, true);
        } catch (IOException | InterruptedException e) {
            LOGGER.warning("proceed done with failure " + resourceDescription);
            throw new RuntimeException(e);
        }
    }

    /**
     * Starts the lock body. When {@code ownsLock} is true the resources were locked for this block: they are
     * published to nested blocks as {@link HeldResources} and released when the body ends. Otherwise this is a
     * reentrant block running inside the owner, which keeps them.
     */
    private static void startBody(
            final LinkedHashMap<String, List<LockableResourceProperty>> lockedResources,
            StepContext context,
            String resourceDescription,
            final String variable,
            boolean ownsLock)
            throws IOException, InterruptedException {
        List<String> resourceNames = new ArrayList<>(lockedResources.keySet());
        BodyInvoker bodyInvoker = context.newBodyInvoker();
        if (ownsLock) {
            bodyInvoker
                    .withCallback(new Callback(resourceNames, resourceDescription))
                    .withContext(HeldResources.merge(context.get(HeldResources.class), resourceNames));
        } else {
            bodyInvoker.withCallback(new ReentrantCallback(resourceNames));
        }
        if (variable != null && !variable.isEmpty()) {
            Map<String, String> resourceLabels = new LinkedHashMap<>();
            for (String resourceName : resourceNames) {
                LockableResource resource = LockableResourcesManager.get().fromName(resourceName);
                resourceLabels.put(resourceName, resource == null ? "" : resource.getLabelsAsString());
            }
            // set the variable for the duration of the block
            bodyInvoker.withContext(EnvironmentExpander.merge(
                    context.get(EnvironmentExpander.class), new EnvironmentExpander() {
                        private static final long serialVersionUID = -3431466225193397896L;

                        @Override
                        public void expand(@NonNull EnvVars env) {
                            Map<String, String> variables = buildLockEnvVars(variable, lockedResources, resourceLabels);
                            if (variables == null) {
                                return;
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
    }

    /**
     * Builds the {@code lockEnvVars} map injected for the duration of a {@code lock()} block, following
     * local semantics exactly: {@code variable="X"} with locked resources {@code r1, r2} yields
     * {@code X="r1,r2"}, {@code X0="r1"}, {@code X0_<prop>=<value>}, {@code X1="r2"}, ... Property env vars
     * are included. The first (index {@code 0}) resource's properties are additionally exposed without the
     * numeric index ({@code X_<prop>=<value>}) for the common single-resource lock (JENKINS-75943).
     * Returns {@code null} when {@code variable} is null/empty.
     *
     * <p>Shared by the local flow ({@link #proceed}) and the remote bridge so the two never drift. All
     * inputs (resource names and property name/value strings) are serializable, so the remote server can
     * generate the same map and bridge it to the client.
     */
    @edu.umd.cs.findbugs.annotations.CheckForNull
    public static Map<String, String> buildLockEnvVars(
            @edu.umd.cs.findbugs.annotations.CheckForNull String variable,
            @NonNull LinkedHashMap<String, List<LockableResourceProperty>> lockedResources) {
        return buildLockEnvVars(variable, lockedResources, Collections.emptyMap());
    }

    /** Builds lock environment variables with indexed labels and a first-resource label alias. */
    @org.kohsuke.accmod.Restricted(org.kohsuke.accmod.restrictions.NoExternalUse.class)
    @edu.umd.cs.findbugs.annotations.CheckForNull
    public static Map<String, String> buildLockEnvVars(
            @edu.umd.cs.findbugs.annotations.CheckForNull String variable,
            @NonNull LinkedHashMap<String, List<LockableResourceProperty>> lockedResources,
            @NonNull Map<String, String> resourceLabels) {
        if (variable == null || variable.isEmpty()) {
            return null;
        }
        LinkedHashMap<String, String> variables = new LinkedHashMap<>();
        variables.put(variable, String.join(",", lockedResources.keySet()));
        int index = 0;
        for (Entry<String, List<LockableResourceProperty>> lockResourceEntry : lockedResources.entrySet()) {
            String lockEnvName = variable + index;
            variables.put(lockEnvName, lockResourceEntry.getKey());
            for (LockableResourceProperty lockProperty : lockResourceEntry.getValue()) {
                variables.put(lockEnvName + "_" + lockProperty.getName(), lockProperty.getValue());
                if (index == 0) {
                    // JENKINS-75943: also expose the first resource's properties without the numeric
                    // index, so ${variable}_<prop> resolves for the common single-resource lock.
                    variables.put(variable + "_" + lockProperty.getName(), lockProperty.getValue());
                }
            }
            String labels = resourceLabels.getOrDefault(lockResourceEntry.getKey(), "");
            variables.put(lockEnvName + "_LABELS", labels);
            if (index == 0) {
                variables.put(variable + "_LABELS", labels);
            }
            ++index;
        }
        return variables;
    }

    /**
     * Adds remote metadata env vars for remote lock blocks while preserving local env semantics.
     * For variable "X" this appends: X_SERVER_ID and X_LOCK_ID.
     */
    @NonNull
    private static Map<String, String> withRemoteMetadata(
            @NonNull Map<String, String> lockEnvVars,
            @edu.umd.cs.findbugs.annotations.CheckForNull String variable,
            @edu.umd.cs.findbugs.annotations.CheckForNull RemoteLockSession remoteSession,
            @NonNull String lockId) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(lockEnvVars);
        if (variable == null || variable.isEmpty() || remoteSession == null) {
            return merged;
        }

        String serverId = remoteSession.getServerId();
        if (serverId != null && !serverId.isEmpty()) {
            merged.put(variable + "_SERVER_ID", serverId);
        }
        merged.put(variable + "_LOCK_ID", lockId);
        return merged;
    }

    /** Serializable {@link EnvironmentExpander} that injects a precomputed env-var map (named/static to avoid capturing the step). */
    private static final class EnvVarsExpander extends EnvironmentExpander {

        private static final long serialVersionUID = -6921365277116143418L;
        private final Map<String, String> envVars;

        EnvVarsExpander(Map<String, String> envVars) {
            this.envVars = envVars;
        }

        @Override
        public void expand(@NonNull EnvVars env) {
            env.overrideAll(envVars);
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
            String message = "Lock released on resource [" + this.resourceDescription + "]";
            if (this.resourceNames != null && !this.resourceNames.isEmpty()) {
                message += ", resources: " + formatResourceNames(this.resourceNames);
            }
            LockableResourcesManager.printLogs(
                    message, Level.FINE, LOGGER, context.get(TaskListener.class).getLogger());
        }
    }

    /** Ends a reentrant block. The resources stay locked: the enclosing block releases them. */
    private static final class ReentrantCallback extends BodyExecutionCallback.TailCall {

        private static final long serialVersionUID = 4409385916418326170L;
        private final List<String> resourceNames;

        ReentrantCallback(List<String> resourceNames) {
            this.resourceNames = resourceNames;
        }

        @Override
        protected void finished(StepContext context) throws Exception {
            LockableResourcesManager.printLogs(
                    "Leaving reentrant lock, resources " + formatResourceNames(this.resourceNames)
                            + " stay locked by the enclosing lock",
                    Level.FINE,
                    LOGGER,
                    context.get(TaskListener.class).getLogger());
        }
    }

    /**
     * Body context listing the resources held by the current lock block and all enclosing ones, so a nested
     * {@code lock(reentrant: true)} can tell they are already owned. Parallel branches that are not inside
     * such a block do not see it, so they still wait for each other.
     */
    static final class HeldResources implements Serializable {

        private static final long serialVersionUID = -5062398724305183520L;
        private final LinkedHashSet<String> names;

        private HeldResources(LinkedHashSet<String> names) {
            this.names = names;
        }

        static HeldResources merge(@CheckForNull HeldResources outer, Collection<String> names) {
            LinkedHashSet<String> merged = new LinkedHashSet<>();
            if (outer != null) {
                merged.addAll(outer.names);
            }
            merged.addAll(names);
            return new HeldResources(merged);
        }
    }

    @SuppressFBWarnings(
            value = {"REC_CATCH_EXCEPTION", "SE_INNER_CLASS"},
            justification = "Best-effort release logging; the callback is serialized as part of the step execution.")
    private final class RemoteCallback extends BodyExecutionCallback.TailCall {

        private static final long serialVersionUID = -6771337682719425678L;

        private final String remoteResource;

        private RemoteCallback(String remoteResource) {
            this.remoteResource = remoteResource;
        }

        @Override
        protected void finished(StepContext context) {
            remoteSession.onBodyFinished(LockStepExecution.this);

            Run<?, ?> build;
            try {
                build = context.get(Run.class);
                LockedResourcesBuildAction.addLog(
                        build, Collections.singletonList(remoteResource), "released", step.toString());
                LockableResourcesManager.printLogs(
                        "Remote lock released on ["
                                + step
                                + "] (serverId="
                                + remoteSession.getServerId()
                                + ", lockId="
                                + remoteSession.getLockId()
                                + ")",
                        Level.FINE,
                        LOGGER,
                        context.get(TaskListener.class).getLogger());
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Failed to write remote release log", ex);
            }
        }
    }

    @Override
    public void stop(@NonNull Throwable cause) {
        if (remoteSession != null) {
            remoteSession.stop(this, cause);
            return;
        }

        boolean cleaned = LockableResourcesManager.get().unqueueContext(getContext());
        if (!cleaned) {
            LOGGER.log(
                    Level.WARNING,
                    "Cannot remove context from lockable resource waiting list. The context is not in the waiting list.");
        }
        getContext().onFailure(cause);
    }

    @Override
    public void onResume() {
        if (remoteSession != null) {
            remoteSession.onResume(this);
        }
    }
}
