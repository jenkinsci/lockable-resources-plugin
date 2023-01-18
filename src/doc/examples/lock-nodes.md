# Examples

## Node dependent resources

Locking a resource that depends on a specific node can be very helpful in many cases.
That means a job must pick a target node that has the requested resource available.

```groovy
// allocate node
node('some-build-node') {
  // Lock resource named *whatever-resource-some-build-node*
  lock("whatever-resource-${env.NODE_NAME}") {
    echo "Running on node ${env.NODE_NAME} with locked resource ${env.LOCKED_RESOURCE}"
  }
}
```

But much more useful is lock node first.

```groovy
// Lock resource named *some-build-node*
lock('some-build-node') {
  // allocate node
  node(env.LOCKED_RESOURCE) {

    echo "I am on node ${env.NODE_NAME} and locked resource ${env.LOCKED_RESOURCE}"
  }
}
```

Let explain in more complex use case.

*Request:*
Our job tests server-client integration. That means we need 2 nodes (1 server and 1 client).
On every node must be test sources up-to-date.
Tests are running only on client side.

*Solution:*
Create 2 nodes:

- node-server
- node-client

and execute it parallel like this:

```groovy
Map stages = [:];
stages['server'] = {
  node('node-server') {
    prepareTests()
    startServer()
  }
}
stages['client'] = {
  node('node-client') {
    prepareTests()
    startClientTest()
  }
}
// execute all prepare stages synchronous
parallel stages

// Prepare tests on node
void prepareTests() {
  checkout([$class: 'GitSCM',
            branches: [[name: 'master']]
           ])
}
// Start server
void startServer() {
    echo 'Server will be started in few seconds'
    sh 'mvn clean hpi:run'
    echo 'Server is done'
}
// Start client
void startClientTest() {
  sleep 20
  sh 'mvn clean verify'
}
```

It looks pretty fine and easy, but !!!.

Executing all steps parallel might leads to timing issues, because checkout on server-node might takes much longer then on client-node. This is serious issue because the client starts before the server and can not connect to server.

The solution is to synchronized parallel stages like this.

```groovy
Map prepareStages = [:];
prepareStages['server'] = {
  node('node-server') {
    prepareTests()
  }
}
prepareStages['client'] = {
  node('node-client') {
    startServer()
  }
}
// execute all prepare stages synchronous
parallel prepareStages

Map testStages = [:]
testStages['server'] = {
  node('node-server') {
    prepareTests();
  }
}
testStages['client'] = {
  node('node-client') {
    sleep 20
    startClientTest();
  }
}

// execute all test stages at the same time
testStages.failFast = true
parallel testStages

...
```

Ok we solve the timing issue, but what is wrong here?

When the step *parallel prepareStages* is done then are on both nodes executors free. At this moment
it might happen, that some other job allocate one of the nodes. This will leads to more side effects, like:

- no body can grant, that currently checked out workspace will be untouched
- no body can grant how long will be the node allocated
- ... and many others

Instead, we lock both nodes with a single call to `lock`.

Create two resources:
name           | Labels |
---------------|--------|
nodes-server-1 | server-node |
nodes-client-1 | client-node  |


```groovy
lock(variable: 'locked_resources',
     extra: [
      [label: 'server-node', quantity: 1],
      [label: 'client-node', quantity: 1]
    ) {
  final String serverNodeName = env.LOCKED_RESOURCE0;
  final String clientNodeName = env.LOCKED_RESOURCE1;
  Map prepareStages = [:];
  prepareStages['server'] = {
    node(serverNodeName) {
      prepareTests()
    }
  }
  prepareStages['client'] = {
    node(clientNodeName) {
      startServer()
    }
  }
  // execute all prepare stages synchronous
  parallel prepareStages

  Map testStages = [:]
  testStages['server'] = {
    node(serverNodeName) {
      prepareTests();
    }
  }
  testStages['client'] = {
    node(clientNodeName) {
      sleep 20
      startClientTest();
    }
  }

  // execute all test stages at the same time
  testStages.failFast = true
  parallel testStages

}

...
```

Keep in mind, that `lock()` only helps when locks are consistently requested for resources.
