package org.jenkins.plugins.lockableresources;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Node;
import hudson.slaves.ComputerListener;
import java.util.Iterator;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import org.jenkins.plugins.lockableresources.util.Constants;

// -----------------------------------------------------------------------------
/** Mirror Jenkins nodes to lockable-resources */
@Extension
public class NodesMirror extends ComputerListener {

    private static final Logger LOGGER = Logger.getLogger(NodesMirror.class.getName());
    private static LockableResourcesManager lrm;

    // ---------------------------------------------------------------------------
    private static boolean isNodeMirrorEnabled() {
        return SystemProperties.getBoolean(Constants.SYSTEM_PROPERTY_ENABLE_NODE_MIRROR);
    }

    // ---------------------------------------------------------------------------
    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void createNodeResources() {
        LOGGER.info("lockable-resources-plugin: configure node resources");
        mirrorNodes();
    }

    // ---------------------------------------------------------------------------
    @Override
    public final void onConfigurationChange() {
        mirrorNodes();
    }

    // ---------------------------------------------------------------------------
    private static void mirrorNodes() {
        if (!isNodeMirrorEnabled()) {
            return;
        }

        LOGGER.info("lockable-resources-plugin: start nodes mirroring");
        lrm = LockableResourcesManager.get();
        synchronized (lrm.syncResources) {
            for (Node n : Jenkins.get().getNodes()) {
                mirrorNode(n);
            }
            // please do not remove it, From time to time is necessary for developer debugs
            // thx
            // lrm.printResources();
            deleteNotExistingNodes();
            // lrm.printResources();
        }
        LOGGER.info("lockable-resources-plugin: nodes mirroring finished");
    }

    // ---------------------------------------------------------------------------
    private static void deleteNotExistingNodes() {
        synchronized (lrm.syncResources) {
            Iterator<LockableResource> resourceIterator = lrm.getResources().iterator();
            while (resourceIterator.hasNext()) {
                LockableResource resource = resourceIterator.next();
                if (!resource.isNodeResource() || (Jenkins.get().getNode(resource.getName()) != null)) {
                    continue;
                }
                if (resource.isFree()) {
                    // we can remove this resource. Is newer used currently
                    LOGGER.config("lockable-resources-plugin: remove node resource '" + resource.getName() + "'.");
                    resourceIterator.remove();
                } else {
                    LOGGER.warning("lockable-resources-plugin: can not remove node-resource '"
                            + resource.getName()
                            + "'. The resource is currently used (not free).");
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    private static void mirrorNode(Node node) {
        if (node == null) {
            return;
        }

        LockableResource nodeResource = lrm.fromName(node.getNodeName());
        boolean exist = nodeResource != null;
        if (!exist) {
            nodeResource = new LockableResource(node.getNodeName());
            LOGGER.config("lockable-resources-plugin: Node-resource '" + nodeResource.getName() + "' will be added.");
        } else {
            LOGGER.fine("lockable-resources-plugin: Node-resource '" + nodeResource.getName() + "' will be updated.");
        }
        nodeResource.setLabels(
                node.getAssignedLabels().stream().map(Object::toString).collect(Collectors.joining(" ")));
        nodeResource.setNodeResource(true);
        nodeResource.setEphemeral(false);
        nodeResource.setDescription(node.getNodeDescription());

        if (!exist) {
            lrm.addResource(nodeResource);
        }
    }
}
