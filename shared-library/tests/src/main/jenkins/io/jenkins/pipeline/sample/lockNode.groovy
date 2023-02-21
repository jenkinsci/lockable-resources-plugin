package io.jenkins.pipeline.sample

@Library('lockable-resources-shared-library')_

// imports are for helpers 
// The step lockNode() self is global and does not need it
import hudson.slaves.JNLPLauncher
import hudson.slaves.DumbSlave
import hudson.slaves.RetentionStrategy
import io.jenkins.library.lockableresources.Utils;

// Utils.globalScope = this

//-----------------------------------------------------------------------------
// Example 1
// lock node by node-name
Map options = [allocateExecutor : false];
createNode('SampleNode1');
lockNode('SampleNode1', options) {
  whereAmI();
}

//-----------------------------------------------------------------------------
// Example 2
// Lock node by node-label matching
createNode('SampleNode2', 'unix ARM');
lockNode('unix', options) {
  whereAmI();
}
// do not allocate executor, just lock node
lockNode('ARM') {
  // allocate 2 executors on the same node
  node(env.LOCKED_RESOURCE) {
    node(env.LOCKED_RESOURCE) {
      whereAmI();
    }
  }
}
lockNode('unix && ARM', options) {
  whereAmI();
}

// do not allocate executor, just lock node
lockNode('ARM') {
  whereAmI();
}

// node AMD does not exits
try {
  lockNode('AMD') {
    whereAmI();
  }
} catch (Exception error) {
  echo error.toString()
}


//-----------------------------------------------------------------------------
// Example 3
// Lock 2 nodes by node-label matching
// and execute the scope in parallel.
createNode('SampleNode3', 'unix ARM');

options = [
  quantity : 2, // count of nodes to allocate
  randomize : false, // do not randomize (per default true)
  sort : false, // do not sort (per default true)
  variable: 'LOCKED_NODES'
];
lockNode('unix && ARM') {
  Map stages = [:]
  stages[env.LOCKED_NODES0] = {
    node(env.LOCKED_NODES0) {
      whereAmI();
    }
  };
  stages[env.LOCKED_NODES1] = {
    node(env.LOCKED_NODES1) {
      whereAmI();
    }
  };
  parallel(stages);
}


//-----------------------------------------------------------------------------
// helpers
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
void whereAmI() {
  echo "locked resource: " + env.LOCKED_RESOURCE;
  echo "locked node: " + env.NODE_NAME;
}

//-----------------------------------------------------------------------------
/*
Approvals
JCaC

security:
  scriptApproval:
    approvedSignatures:
    - "method hudson.model.Computer getHostName"
    - "method hudson.model.Computer isOnline"
    - "method hudson.model.Node setLabelString java.lang.String"
    - "method hudson.model.Slave setMode hudson.model.Node$Mode"
    - "method hudson.model.Slave setNodeDescription java.lang.String"
    - "method hudson.model.Slave setNumExecutors int"
    - "method hudson.model.Slave setRetentionStrategy hudson.slaves.RetentionStrategy"
    - "method jenkins.model.Jenkins addNode hudson.model.Node"
    - "method jenkins.model.Jenkins getComputer java.lang.String"
    - "method hudson.slaves.SlaveComputer getJnlpMac"
    - "method jenkins.model.Jenkins getRootUrl"
    - "new hudson.slaves.DumbSlave java.lang.String java.lang.String hudson.slaves.ComputerLauncher"
    - "new hudson.slaves.JNLPLauncher boolean"
    - "new hudson.slaves.RetentionStrategy$Always"
    - "staticField hudson.model.Node$Mode NORMAL"
    - "staticMethod jenkins.model.Jenkins getInstance"
    - "staticMethod jenkins.model.Jenkins get"
    - "staticMethod org.codehaus.groovy.runtime.DefaultGroovyMethods execute java.lang.String"
    - "staticMethod org.codehaus.groovy.runtime.ProcessGroovyMethods getText java.lang.Process"
*/
void createNode(final String nodeName, final String labels = 'LabelA labelB os:Windows country:Austria') {

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

  // mirror node to resource, be sure the resource exists
  mirrorNodesToLockableResources(nodeName);

  // try to start the node.
  // final String secret = jenkins.model.Jenkins.instance.getComputer(nodeName).getJnlpMac();
  // final String cmd = 'java -jar agent.jar -jnlpUrl ' + Jenkins.get().getRootUrl() + '/manage/computer/' + nodeName + '/jenkins-agent.jnlp -secret ' + secret + ' -workDir "' + tempWorkspace + '" & exit 0';
  // echo cmd.execute().text;
}
