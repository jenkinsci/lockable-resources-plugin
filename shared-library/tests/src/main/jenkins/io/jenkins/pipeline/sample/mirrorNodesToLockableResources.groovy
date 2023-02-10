package io.jenkins.pipeline.sample

@Library('lockable-resources-shared-library')_

// imports are for helpers 
// The step mirrorNodesToLockableResources() self is global and does
// not need it
import hudson.slaves.JNLPLauncher
import hudson.slaves.DumbSlave
import hudson.slaves.RetentionStrategy

//-----------------------------------------------------------------------------
// Example 1
// mirror only one node.
// This will mirror one-by-one the node Node1 into lockable-resource with
// + name
// + descriptions
// + labels
// Note: create the node Node1 first
createNode('SampleNode1')
mirrorNodesToLockableResources('SampleNode1')

//-----------------------------------------------------------------------------
// Example 2
// Mirror node with user defined properties
// This will mirror Node2 with customized properties:
// name: Node-<originNodeName>
// labels: <originLabels> extends with label 'mirrored-node'
// description:  will contains hostname in case the node is online.

// The best way to change default behavior is to create Groovy expression
// and assign it to options.nodeToResourceProperties
// It will looks like this
Map options = [:]
options.nodeToResourceProperties = {computer, properties ->
  if (computer.isOnline()) {
    properties.description += 'Hostname: ' + computer.getHostName()
  }
  properties.name = 'Node-' + properties.name
  properties.labels += ' mirrored-node'
}
// Note: create the node Node2 first
createNode('SampleNode2')
mirrorNodesToLockableResources('SampleNode2', options)


//-----------------------------------------------------------------------------
// Example 3
// Mirror all nodes to lockable resources.
// When the resource does not exists it will be add automatically
// All resources mirrored from node contains label 'node'
createNode('SampleNode3')
createNode('SampleNode4')
createNode('SampleNode5')
mirrorNodesToLockableResources();


//-----------------------------------------------------------------------------
// Example 4
// Mirror all nodes with customized properties
// For *options* see also Example 2
createNode('SampleNode6')
createNode('SampleNode7')
mirrorNodesToLockableResources(options)

//-----------------------------------------------------------------------------
// In case the node does not exist is the mirroring skipped
mirrorNodesToLockableResources('This-node does not exist')



//-----------------------------------------------------------------------------
// helpers
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
/*
Approvals
method hudson.model.Computer getHostName
method hudson.model.Computer isOnline
method hudson.model.Node setLabelString java.lang.String
method hudson.model.Slave setMode hudson.model.Node$Mode
method hudson.model.Slave setNodeDescription java.lang.String
method hudson.model.Slave setNumExecutors int
method hudson.model.Slave setRetentionStrategy hudson.slaves.RetentionStrategy
method jenkins.model.Jenkins addNode hudson.model.Node
new hudson.slaves.DumbSlave java.lang.String java.lang.String hudson.slaves.ComputerLauncher
new hudson.slaves.JNLPLauncher boolean
new hudson.slaves.RetentionStrategy$Always
staticField hudson.model.Node$Mode NORMAL
staticMethod jenkins.model.Jenkins getInstance

*/
def createNode(final String nodeName, final String labels = 'LabelA labelB os:Windows country:Austria') {

    echo 'Create new node: ' + nodeName

    String tempWorkspace = 'C:\\jenkins\\tmp';

    // Define a "Permanent Agent"

    final boolean enableWorkDir = true; // https://javadoc.jenkins.io/hudson/slaves/JNLPLauncher.html#%3Cinit%3E(boolean)
    final launcher = new JNLPLauncher(enableWorkDir);
    
    Slave agent = new DumbSlave(nodeName,
                                tempWorkspace,
                                launcher
                                );

    agent.nodeDescription = 'Auto generated agent';
    agent.numExecutors = 1;
    agent.labelString = labels;
    agent.mode = Node.Mode.NORMAL;
    agent.retentionStrategy = new RetentionStrategy.Always();
    Jenkins.instance.addNode(agent);
    return true;
  }
