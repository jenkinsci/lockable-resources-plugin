package org.jenkins.plugins.lockableresources;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.StepContext;

public class FindLocksStepExecution extends AbstractStepExecutionImpl implements Serializable {

  private static final long serialVersionUID = -5757385070025969380L;
  private static final Logger LOGGER = Logger.getLogger(FindLocksStepExecution.class.getName());

  private final FindLocksStep step;

  public FindLocksStepExecution(FindLocksStep step, StepContext context) {
    super(context);
    this.step = step;
  }

  @Override
  public boolean start() throws Exception {
    List<LockableResource> allResources = LockableResourcesManager.get().getResources();

    List<Map<String,Object>> resourcesAsMap = allResources.stream()
      .filter(step::asPredicate)
      .map(FindLocksStepExecution::convertResourceToMap)
      .collect(Collectors.toList());

    getContext().onSuccess(resourcesAsMap);
    return true;
  }

  private static Map<String, Object> convertResourceToMap(LockableResource lockableResource) {
    Map<String, Object> lockAsMap = new HashMap<>();
    lockAsMap.put("name", lockableResource.getName());
    lockAsMap.put("labels", lockableResource.getLabels());
    lockAsMap.put("note", lockableResource.getNote());
    lockAsMap.put("description", lockableResource.getDescription());
    lockAsMap.put("reservedBy", lockableResource.getReservedBy());
    lockAsMap.put("reservedTimestamp", lockableResource.getReservedTimestamp());
    lockAsMap.put("queuedItemProject", lockableResource.getQueueItemProject());
    lockAsMap.put("buildName", lockableResource.getBuildName());
    lockAsMap.put("queuedItemId", lockableResource.getQueueItemId());
    lockAsMap.put("lockCause", lockableResource.getLockCause());
    lockAsMap.put("reservedByEmail", lockableResource.getReservedByEmail());
    return lockAsMap;
  }
}
