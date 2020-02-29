package org.jenkins.plugins.lockableresources;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.logging.Logger;

/** Passed to the pipeline script to let the script call the plugin as needed. */
public class LockObject implements Serializable {

  private static final Logger LOGGER = Logger.getLogger(LockObject.class.getName());
  private static final long serialVersionUID = 4116290837594859631L;
  private final String buildExternalizableId;
  private final List<LockableResource> lockableResources;

  public LockObject(
      final String buildExternalizableId,
      final List<LockableResource> lockableResources)
      throws IOException, InterruptedException {
    this.buildExternalizableId = buildExternalizableId;
    this.lockableResources = lockableResources;
  }

  public boolean release() throws IOException, InterruptedException {

    if (this.lockableResources.isEmpty()) {
      LOGGER.fine("Cannot release any locks as none are acquired");
      return false;
    }

    LockableResourcesManager.get().unlock(this.lockableResources, this.buildExternalizableId);
    LOGGER.fine("Lock released on [" + this.lockableResources + "]");
    return true;
  }

  @Override
  public String toString() {
    return "LockObject [buildExternalizableId="
        + this.buildExternalizableId
        + ", lockableResources="
        + this.lockableResources
        + "]";
  }
}
