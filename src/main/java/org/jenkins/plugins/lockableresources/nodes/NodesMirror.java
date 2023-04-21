

package org.jenkins.plugins.lockableresources;


import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.Extension;
import hudson.model.Node;
import hudson.slaves.ComputerListener;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import org.jenkins.plugins.lockableresources.util.Constants;

//-----------------------------------------------------------------------------
/** Mirror Jenkins nodes to lockable-resources
*/
@Extension
public class NodesMirror extends ComputerListener {

  private static final Logger LOG = Logger.getLogger(NodesMirror.class.getName());

  //---------------------------------------------------------------------------
  private static boolean isNodeMirrorEnabled() {
    return SystemProperties.getBoolean(Constants.SYSTEM_PROPERTY_ENABLE_NODE_MIRROR);
  }

  //---------------------------------------------------------------------------
  @Initializer(after = InitMilestone.JOB_LOADED)
  public static void createNodeResources() {
    LOG.log(Level.FINE, "lockable-resources-plugin configure node resources");
    mirrorNodes();
  }

  //---------------------------------------------------------------------------
  @Override
  public final void onConfigurationChange() {
    mirrorNodes();
  }

  //---------------------------------------------------------------------------
  private static void mirrorNodes() {
    if (!isNodeMirrorEnabled()) {
      return;
    }

    deleteExistingNodes();

    for (Node n : Jenkins.get().getNodes()) {
      mirrorNode(n);
    }
  }

  //---------------------------------------------------------------------------
  private static void deleteExistingNodes() {
    LockableResourcesManager lrm = LockableResourcesManager.get();
    Iterator<LockableResource> resourceIterator = lrm.getResources().iterator();
    while (resourceIterator.hasNext()) {
      LockableResource resource = resourceIterator.next();
      if (!resource.isNodeResource()) {
        continue;
      }
      if (resource.isFree()) {
        // we can remove this resource. Is newer used currently
        resourceIterator.remove();
      } else {
        LOG.log(Level.FINE, "lockable-resources-plugin skip node deletion of: " + resource.getName() + ". Reason: Currently locked");
      }
    }
  }

  //---------------------------------------------------------------------------
  private static void mirrorNode(Node node) {
    if (node == null) {
      return;
    }

    LockableResourcesManager lrm = LockableResourcesManager.get();
    LockableResource nodeResource = lrm.fromName(node.getNodeName());
    boolean exist = nodeResource != null;
    if (!exist) {
      nodeResource = new LockableResource(node.getNodeName());
    }
    nodeResource.setLabels(node.getAssignedLabels().stream().map(Object::toString).collect(Collectors.joining(" ")));
    nodeResource.setNodeResource(true);
    nodeResource.setEphemeral(false);
    nodeResource.setDescription(node.getNodeDescription());
    LOG.log(Level.FINE, "lockable-resources-plugin add node-resource: " + nodeResource.getName());
    if (!exist) {
      lrm.getResources().add(nodeResource);
    }
  }
}