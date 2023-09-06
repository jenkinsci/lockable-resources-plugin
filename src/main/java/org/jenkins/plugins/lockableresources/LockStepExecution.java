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
    step.validate();

    getContext().get(FlowNode.class).addAction(new PauseAction("Lock"));
    PrintStream logger = getContext().get(TaskListener.class).getLogger();

    ResourceSelectStrategy resourceSelectStrategy = ResourceSelectStrategy.valueOf(step.resourceSelectStrategy.toUpperCase(Locale.ENGLISH));

    Run<?, ?> run = getContext().get(Run.class);
    logger.println("Trying to acquire lock on [" + step + "]");

    List<LockableResourcesStruct> resourceHolderList = new ArrayList<>();

    LockableResourcesManager lrm = LockableResourcesManager.get();
    List<LockableResource> available = null;
    LinkedHashMap<String, List<LockableResourceProperty>> resourceNames = new LinkedHashMap<>();
    synchronized (lrm.syncResources) {
      for (LockStepResource resource : step.getResources()) {
        List<String> resources = new ArrayList<>();
        if (resource.resource != null) {
          if (lrm.createResource(resource.resource)) {
            logger.println("Resource [" + resource + "] did not exist. Created.");
          }
          resources.add(resource.resource);
        }
        resourceHolderList.add(
            new LockableResourcesStruct(resources, resource.label, resource.quantity));
      }

      // determine if there are enough resources available to proceed
      available =
          lrm.checkResourcesAvailability(
              resourceHolderList, logger, null, step.skipIfLocked, resourceSelectStrategy);

      if (available == null) {
        onLockFailed(logger, resourceHolderList);
        return false;
      }

      final boolean lockFailed = (lrm.lock(available, run) == false);


      if (lockFailed) {
        // this here is very defensive code, and you will probably never hit it. (hopefully)
        onLockFailed(logger, resourceHolderList);
        return true;
      }

      // since LockableResource contains transient variables, they cannot be correctly serialized
      // hence we use their unique resource names and properties
      for (LockableResource resource : available) {
        resourceNames.put(resource.getName(), resource.getProperties());
      }
    }
    LockStepExecution.proceed(resourceNames, getContext(), step.toString(), step.variable, step.inversePrecedence);

    return false;
  }

  // ---------------------------------------------------------------------------
  /**
   * Executed when the lock() function fails. No available resources, or we failed to lock available
   * resources if the resource is known, we could output the active/blocking job/build
   */
  private void onLockFailed(PrintStream logger, List<LockableResourcesStruct> resourceHolderList) {
    LockableResourcesManager lrm = LockableResourcesManager.get();
    LockableResource resource = step.resource != null ? lrm.fromName(step.resource) : null;
    String logMessage = "";

    if (resource != null) {
      logMessage += resource.getLockCause();
    } else {
      logMessage += "[" + step + "] is not free";
    }

    if (step.skipIfLocked) {
      logMessage += ", skipping execution...";
      logger.println(logMessage);
      getContext().onSuccess(null);
    } else {
      logMessage += ", waiting for execution...";
      logger.println(logMessage);
      lrm.queueContext(getContext(), resourceHolderList, step.toString(), step.variable);
    }
  }

  // ---------------------------------------------------------------------------
  @SuppressFBWarnings(
      value = "REC_CATCH_EXCEPTION",
      justification = "not sure which exceptions might be catch.")
  public static void proceed(
      final LinkedHashMap<String, List<LockableResourceProperty>> lockedResources,
      StepContext context,
      String resourceDescription,
      final String variable,
      boolean inversePrecedence) {
    Run<?, ?> r;
    FlowNode node = null;
    PrintStream logger = null;
    try {
      r = context.get(Run.class);
      node = context.get(FlowNode.class);
      logger = context.get(TaskListener.class).getLogger();
      logger.println("Lock acquired on [" + resourceDescription + "]");
    } catch (Exception e) {
      /// FIXME: this is dangerous, because we never free the PauseAction
      context.onFailure(e);
      return;
    }

    LOGGER.finest("Lock acquired on [" + resourceDescription + "] by " + r.getExternalizableId());
    try {
      PauseAction.endCurrentPause(node);
      BodyInvoker bodyInvoker =
          context
              .newBodyInvoker()
              .withCallback(
                  new Callback(
                      new ArrayList<>(lockedResources.keySet()),
                      resourceDescription,
                      inversePrecedence));
      if (variable != null && variable.length() > 0) {
        // set the variable for the duration of the block
        bodyInvoker.withContext(
            EnvironmentExpander.merge(
                context.get(EnvironmentExpander.class),
                new EnvironmentExpander() {
                  private static final long serialVersionUID = -3431466225193397896L;

                  @Override
                  public void expand(@NonNull EnvVars env) {
                    final LinkedHashMap<String, String> variables = new LinkedHashMap<>();
                    final String resourceNames =
                        lockedResources.keySet().stream().collect(Collectors.joining(","));
                    variables.put(variable, resourceNames);
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
                    LOGGER.finest(
                        "Setting "
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
      LOGGER.info("proceed done with failure " + resourceDescription);
      throw new RuntimeException(e);
    }
  }

  private static final class Callback extends BodyExecutionCallback.TailCall {

    private static final long serialVersionUID = -2024890670461847666L;
    private final List<String> resourceNames;
    private final String resourceDescription;
    private final boolean inversePrecedence;

    Callback(List<String> resourceNames, String resourceDescription, boolean inversePrecedence) {
      this.resourceNames = resourceNames;
      this.resourceDescription = resourceDescription;
      this.inversePrecedence = inversePrecedence;
    }

    @Override
    protected void finished(StepContext context) throws Exception {
      LockableResourcesManager.get()
          .unlockNames(this.resourceNames, context.get(Run.class), this.inversePrecedence);
      context
          .get(TaskListener.class)
          .getLogger()
          .println("Lock released on resource [" + resourceDescription + "]");
      LOGGER.finest("Lock released on [" + resourceDescription + "]");
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
