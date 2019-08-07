package org.jenkins.plugins.lockableresources;

import hudson.EnvVars;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
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
    logger.println("Trying to acquire lock on [" + step + "]");

    List<LockableResourcesStruct> resourceHolderList = new ArrayList<>();

    for (LockStepResource resource : step.getResources()) {
      List<String> resources = new ArrayList<>();
      if (resource.resource != null) {
        if (LockableResourcesManager.get().createResource(resource.resource)) {
          logger.println("Resource [" + resource + "] did not exist. Created.");
        }
        resources.add(resource.resource);
      }
      resourceHolderList.add(
          new LockableResourcesStruct(resources, resource.label, resource.quantity));
    }

    // determine if there are enough resources available to proceed
    Set<LockableResource> available =
        LockableResourcesManager.get()
            .checkResourcesAvailability(resourceHolderList, logger, null, step.skipIfLocked);
    Run<?, ?> run = getContext().get(Run.class);
    if (available == null
        || !LockableResourcesManager.get()
            .lock(
                available,
                run,
                getContext(),
                step.toString(),
                step.variable,
                step.inversePrecedence)) {
      // if the resource is known, we could output the active/blocking job/build
      LockableResource resource = LockableResourcesManager.get().fromName(step.resource);
      boolean buildNameKnown = resource != null && resource.getBuildName() != null;
      if (step.skipIfLocked) {
        if (buildNameKnown) {
          logger.println(
              "[" + step + "] is locked by " + resource.getBuildName() + ", skipping execution...");
        } else {
          logger.println("[" + step + "] is locked, skipping execution...");
        }
        getContext().onSuccess(null);
        return true;
      } else {
        if (buildNameKnown) {
          logger.println("[" + step + "] is locked by " + resource.getBuildName() + ", waiting...");
        } else {
          logger.println("[" + step + "] is locked, waiting...");
        }
        LockableResourcesManager.get()
            .queueContext(getContext(), resourceHolderList, step.toString(), step.variable);
      }
    } // proceed is called inside lock if execution is possible
    return false;
  }

  public static void proceed(
      final List<String> resourcenames,
      StepContext context,
      String resourceDescription,
      final String variable,
      boolean inversePrecedence) {
    Run<?, ?> r = null;
    FlowNode node = null;
    try {
      r = context.get(Run.class);
      node = context.get(FlowNode.class);
      context
          .get(TaskListener.class)
          .getLogger()
          .println("Lock acquired on [" + resourceDescription + "]");
    } catch (Exception e) {
      context.onFailure(e);
      return;
    }

    LOGGER.finest("Lock acquired on [" + resourceDescription + "] by " + r.getExternalizableId());
    try {
      PauseAction.endCurrentPause(node);
      BodyInvoker bodyInvoker =
          context
              .newBodyInvoker()
              .withCallback(new Callback(resourcenames, resourceDescription, inversePrecedence));
      if (variable != null && variable.length() > 0) {
        // set the variable for the duration of the block
        bodyInvoker.withContext(
            EnvironmentExpander.merge(
                context.get(EnvironmentExpander.class),
                new EnvironmentExpander() {
                  private static final long serialVersionUID = -3431466225193397896L;

                  @Override
                  public void expand(EnvVars env) throws IOException, InterruptedException {
                    final String resources = String.join(",", resourcenames);
                    LOGGER.finest(
                        "Setting ["
                            + variable
                            + "] to ["
                            + resources
                            + "] for the duration of the block");

                    env.override(variable, resources);
                  }
                }));
      }
      bodyInvoker.start();
    } catch (IOException | InterruptedException e) {
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
  public void stop(Throwable cause) throws Exception {
    boolean cleaned = LockableResourcesManager.get().unqueueContext(getContext());
    if (!cleaned) {
      LOGGER.log(
          Level.WARNING,
          "Cannot remove context from lockable resource waiting list. The context is not in the waiting list.");
    }
    getContext().onFailure(cause);
  }
}
