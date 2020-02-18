package org.jenkins.plugins.lockableresources;

import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;

public class LockHelper {

  public static Collection<LockStepResource> getResources(final LockAttributes lockAttributes) {

    final List<LockStepResource> resources = new ArrayList<>();
    if (lockAttributes.getResource() != null || lockAttributes.getLabel() != null) {
      resources.add(
          new LockStepResource(
              lockAttributes.getResource(),
              lockAttributes.getLabel(),
              lockAttributes.getQuantity()));
    }

    if (lockAttributes.getExtra() != null) {
      resources.addAll(lockAttributes.getExtra());
    }
    return resources;
  }

  public static String toString(final LockAttributes lockAttributes) {
    if (lockAttributes.getExtra() != null && !lockAttributes.getExtra().isEmpty()) {
      return LockHelper.getResources(lockAttributes).stream()
          .map(resource -> "{" + resource.toString() + "}")
          .collect(Collectors.joining(","));
    } else if (lockAttributes.getResource() != null || lockAttributes.getLabel() != null) {
      return LockStepResource.toString(
          lockAttributes.getResource(), lockAttributes.getLabel(), lockAttributes.getQuantity());
    } else {
      return "nothing";
    }
  }

  public static boolean start(
      final boolean queIfLocked, final LockAttributes lockAttributes, final StepContext stepContext)
      throws Exception {
    LockStepResource.validate(
        lockAttributes.getResource(), lockAttributes.getLabel(), lockAttributes.getQuantity());
    stepContext.get(FlowNode.class).addAction(new PauseAction("Lock"));
    final Run<?, ?> run = stepContext.get(Run.class);
    final TaskListener listener = stepContext.get(TaskListener.class);

    listener
        .getLogger()
        .println("Trying to acquire lock on [" + LockHelper.toString(lockAttributes) + "]");

    final List<LockableResourcesStruct> resourceHolderList = new ArrayList<>();

    for (final LockStepResource resource : LockHelper.getResources(lockAttributes)) {
      final List<String> resources = new ArrayList<>();
      if (resource.resource != null) {
        if (LockableResourcesManager.get().createResource(resource.resource)) {
          listener.getLogger().println("Resource [" + resource + "] did not exist. Created.");
        }
        resources.add(resource.resource);
      }
      resourceHolderList.add(
          new LockableResourcesStruct(resources, resource.label, resource.quantity));
    }

    // determine if there are enough resources available to proceed
    final Set<LockableResource> available =
        LockableResourcesManager.get()
            .checkResourcesAvailability(resourceHolderList, listener.getLogger(), null);
    final boolean lockWasNotAvailabel =
        available == null
            || !LockableResourcesManager.get()
                .lock(
                    available,
                    run,
                    stepContext,
                    LockHelper.toString(lockAttributes),
                    lockAttributes.getVariable(),
                    lockAttributes.getInversePrecedence());
    if (lockWasNotAvailabel) {
      // if the resource is known, we could output the active/blocking job/build
      final LockableResource resource =
          LockableResourcesManager.get().fromName(lockAttributes.getResource());
      if (queIfLocked && resource != null && resource.getBuildName() != null) {
        listener
            .getLogger()
            .println(
                "["
                    + LockHelper.toString(lockAttributes)
                    + "] is locked by "
                    + resource.getBuildName()
                    + ", waiting...");

      } else if (queIfLocked) {
        listener
            .getLogger()
            .println("[" + LockHelper.toString(lockAttributes) + "] is locked, waiting...");
      }
      if (queIfLocked) {
        // proceed is called inside lock if execution is possible
        LockableResourcesManager.get()
            .queueContext(
                stepContext,
                resourceHolderList,
                LockHelper.toString(lockAttributes),
                lockAttributes.getVariable());
      } else {
        listener
            .getLogger()
            .println("[" + LockHelper.toString(lockAttributes) + "] was not available");
        return false;
      }
      return true;
    } else {
      listener.getLogger().println("[" + LockHelper.toString(lockAttributes) + "] was available");
      return true;
    }
  }
}
