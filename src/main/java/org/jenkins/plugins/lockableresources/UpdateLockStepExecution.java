package org.jenkins.plugins.lockableresources;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.StepContext;

public class UpdateLockStepExecution extends AbstractStepExecutionImpl implements Serializable {

  private static final long serialVersionUID = 1583205294263267002L;

  private static final Logger LOGGER = Logger.getLogger(UpdateLockStepExecution.class.getName());

  private final UpdateLockStep step;

  public UpdateLockStepExecution(UpdateLockStep step, StepContext context) {
    super(context);
    this.step = step;
  }

  @Override
  public boolean start() throws Exception {
    this.step.validate();

    if (this.step.deleteResource) {
      LockableResourcesManager.get().deleteResource(this.step.resource);
    }
    else {
      LockableResource resource = LockableResourcesManager.get().fromName(this.step.resource);
      if (resource == null && this.step.createResource) {
        LockableResourcesManager.get().createResource(this.step.resource);
        resource = LockableResourcesManager.get().fromName(this.step.resource);
        resource.setEphemeral(false);
      }

      if (this.step.setLabels != null) {
        List<String> setLabels = Arrays.asList(this.step.setLabels.trim().split("\\s+"));
        resource.setLabels(setLabels.stream().collect(Collectors.joining(" ")));
      } else if (this.step.addLabels != null || this.step.removeLabels != null) {
        List<String> labels = new ArrayList<>(Arrays.asList(resource.getLabels().split("\\s+")));
        if (this.step.addLabels != null) {
          List<String> addLabels = Arrays.asList(this.step.addLabels.trim().split("\\s+"));
          addLabels.stream().filter(l -> labels.contains(l) == false).forEach(labels::add);
        }
        if (this.step.removeLabels != null) {
          List<String> removeLabels = Arrays.asList(this.step.removeLabels.trim().split("\\s+"));
          labels.removeAll(removeLabels);
        }
        resource.setLabels(labels.stream().collect(Collectors.joining(" ")));
      }

      if (this.step.setNote != null) {
        resource.setNote(this.step.setNote);
      }

      LockableResourcesManager.get().save();
    }

    getContext().onSuccess(null);
    return true;
  }
}
