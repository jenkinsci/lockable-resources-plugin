package org.jenkins.plugins.lockableresources;

import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.workflow.steps.StepContext;

/** Passed to the pipeline script to let the script call the plugin as needed. */
public class LockObject implements Serializable {

  private static final long serialVersionUID = 4116290837594859631L;
  private final List<String> resourceNames;
  private final StepContext context;

  public LockObject(StepContext context, List<String> resourceNames) {
    this.context = context;
    this.resourceNames = resourceNames;
  }

  public List<String> getResourceNames() {
    return resourceNames;
  }

  public boolean release() throws IOException, InterruptedException {
    final Run<?, ?> build = context.get(Run.class);
    final PrintStream logger = context.get(TaskListener.class).getLogger();
    final List<LockableResource> acquiredByJob =
        LockableResourcesManager.get().getResourcesFromBuild(build);

    if (acquiredByJob.isEmpty()) {
      logger.println("Cannot release any locks as none are acquired");
      return false;
    }

    List<LockableResource> toRelease =
        acquiredByJob.stream()
            .filter(t -> this.resourceNames.contains(t.getName()))
            .collect(Collectors.toList());
    if (toRelease.isEmpty()) {
      logger.println(
          "Cannot release lock "
              + resourceNames
              + " as they are not acquired by the job: "
              + acquiredByJob);
      return false;
    }

    LockableResourcesManager.get().unlock(toRelease, build);
    logger.println("Lock released on [" + toRelease + "]");
    return true;
  }

  @Override
  public String toString() {
    return "LockObject [resourceNames=" + resourceNames + "]";
  }
}
