

package org.jenkins.plugins.lockableresources;


import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.Extension;
import hudson.model.Node;
import hudson.slaves.ComputerListener;
import groovy.lang.Binding;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;

//-----------------------------------------------------------------------------
/** Mirror Jenkins nodes to lockable-resources
*/
@Extension
public class NodesMirror extends ComputerListener {

  private static final Logger LOGGER = Logger.getLogger(NodesMirror.class.getName());

  //---------------------------------------------------------------------------
  private static boolean isNodeMirrorEnabled() {
    return SystemProperties.getBoolean(Constants.SYSTEM_PROPERTY_ENABLE_NODE_MIRROR);
  }

  //---------------------------------------------------------------------------
  @Initializer(after = InitMilestone.JOB_LOADED)
  public static void createNodeResources() {
    LOGGER.log(Level.FINE, "lockable-resources-plugin configure node resources");
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

    this.script = LockableResourcesManager.get().getNodeMatchScript();
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
        // Removed locks became ephemeral.
        resource.setDescription("");
        resource.setLabels("");
        resource.setNote("");
        resource.setEphemeral(true);
      }
    }
  }

  //---------------------------------------------------------------------------
  private static void mirrorNode(Node node) {
    if (node == null) {
      return;
    }

    if (this.scriptMatches(node) == false) {
      return;
    }

    LockableResourcesManager lrm = LockableResourcesManager.get();
    LockableResource nodeResource = lrm.fromName(node.getNodeName());
    boolean exist = nodeResource != null;
    if (!exist) {
      nodeResource = new LockableResource(node.getNodeName());
    }
    nodeResource.setLabels(node.getAssignedLabels().stream().map(Object::toString).collect(Collectors.joining(" ")));
    nodeResource.setDescription(node.getNodeDescription());
    if (!exist) {
      LOGGER.log(Level.FINE, "lockable-resources-plugin add node-resource: " + nodeResource.getName());
      nodeResource.setNodeResource(true);
      nodeResource.setEphemeral(false);
      lrm.getResources().add(nodeResource);
    }
  }

  private SecureGroovyScript script = null;
  private boolean scriptMatches(Node node) throws ExecutionException {
    if (node == null) {
      return false;
    }

    if (script == null) {
      return true;
    }
    Binding binding = new Binding();
    binding.setVariable("nodeName", node.getNodeName());
    binding.setVariable("nodeDescription", node.getNodeDescription());
    binding.setVariable("nodeLabels", node.getAssignedLabels());
    try {
      Object result =
        script.evaluate(Jenkins.get().getPluginManager().uberClassLoader, binding, null);
      if (LOGGER.isLoggable(Level.FINE)) {
        LOGGER.fine(
          "Checked node "
            + name
            + " for "
            + script.getScript()
            + " with "
            + binding
            + " -> "
            + result);
      }
      return (Boolean) result;
    } catch (Exception e) {
      throw new ExecutionException(
        "Cannot get boolean result out of groovy expression. See system log for more info", e);
    }
  }
}