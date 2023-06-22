

package org.jenkins.plugins.lockableresources;


import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.Extension;
import hudson.model.labels.LabelAtom;
import hudson.model.Node;
import hudson.slaves.ComputerListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Set;
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
  private static LockableResourcesManager lrm = LockableResourcesManager.get();

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

    synchronized (lrm) {
      deleteExistingNodes();

      for (Node n : Jenkins.get().getNodes()) {
        mirrorNode(n);
      }
    }
  }

  //---------------------------------------------------------------------------
  private static void deleteExistingNodes() {

    Iterator<LockableResource> resourceIterator = lrm.getResources().iterator();
    while (resourceIterator.hasNext()) {
      LockableResource resource = resourceIterator.next();
      if (!resource.isNodeResource()) {
        continue;
      }
      if (Jenkins.get().getNode(resource.getName()) != null) {
        // the node exist, do not remove the resource
        continue;
      }
      if (!resource.isFree()) {
        // the resource is still used somewhere. Do not remove it
        continue;
      }
      resourceIterator.remove();
    }

  }

  //---------------------------------------------------------------------------
  private static void mirrorNode(Node node) {
    if (node == null) {
      return;
    }

    LockableResource nodeResource = new LockableResource(node.getNodeName());

    Set<LabelAtom> assignedLabels = new HashSet<>(node.getAssignedLabels());
    assignedLabels.remove(node.getSelfLabel());
    nodeResource.setLabels(assignedLabels.stream().map(Object::toString).collect(Collectors.joining(" ")));
    nodeResource.setNodeResource(true);
    nodeResource.setEphemeral(false);
    nodeResource.setDescription(node.getNodeDescription());

    LockableResource origNodeResource = lrm.fromName(node.getNodeName());

    if (origNodeResource == null) {
      LOG.log(Level.FINE, "lockable-resources-plugin add node-resource: " + nodeResource.getName());
      lrm.getResources().add(nodeResource);
    } else if (!nodeResource.equals(origNodeResource)) {
      LOG.log(Level.FINE, "lockable-resources-plugin change in node-resource: " + nodeResource.getName());
    } else {
      LOG.log(Level.FINE, "lockable-resources-plugin no-change in node-resource: " + nodeResource.getName());
    }
  }
}