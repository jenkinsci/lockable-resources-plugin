# Avoiding Executor Starvation

When a pipeline uses `agent any` (or a specific label) at the top level together
with `options { lock(...) }`, every build allocates an executor **before**
trying to acquire the lock. If the resource is busy the build sits on the
executor doing nothing — preventing other jobs from using it. This is called
**executor starvation**.

## The problem

```groovy
pipeline {
  agent {
    label 'some-label'
  }
  options {
    lock('end-to-end-test-resource')
  }
  stages {
    stage('Test') {
      steps {
        echo 'Running end-to-end tests...'
      }
    }
  }
}
```

With this layout, if three builds of the same job are queued and there is only
one `end-to-end-test-resource`, all three grab an executor yet only one can
proceed. The other two hold their executors hostage while they wait for the
lock, blocking any other job that needs to run on those executors.

## The solution — `agent none` + stage-level agent

Move the `agent` directive **inside the stage** that needs the lock:

```groovy
pipeline {
  agent none                              // no executor allocated up front
  stages {
    stage('Test') {
      options {
        lock('end-to-end-test-resource')  // acquire lock first …
      }
      agent {
        label 'some-label'                // … then allocate an executor
      }
      steps {
        echo 'Running end-to-end tests...'
      }
    }
  }
}
```

Now a build waiting for the lock does **not** occupy an executor. Once the lock
is acquired the stage allocates the agent and runs. Other jobs can use the
executors in the meantime.

## Preparation stages that don't need the lock

If your build has work that can run without the resource, split it into a
separate stage so the lock is held only when necessary:

```groovy
pipeline {
  agent none
  stages {
    stage('Build') {
      agent any
      steps {
        echo 'Compiling — no lock needed'
      }
    }
    stage('Deploy') {
      options {
        lock resource: 'deploy-target'
      }
      agent any
      steps {
        echo 'Deploying — lock held'
      }
    }
  }
}
```

## Multiple stages under one lock

If several stages need the same resource, wrap them in a parent stage:

```groovy
pipeline {
  agent none
  stages {
    stage('Deploy and Test') {
      options {
        lock resource: 'test-environment'
      }
      agent any
      stages {
        stage('Deploy') {
          steps {
            echo 'Deploying...'
          }
        }
        stage('Integration Test') {
          steps {
            echo 'Testing...'
          }
        }
      }
    }
  }
}
```

The lock is acquired once for the parent stage and released after the last
nested stage completes. No executor is consumed while waiting.

## Scripted pipeline equivalent

In scripted pipelines the same principle applies — acquire the lock **before**
allocating a node:

```groovy
lock('end-to-end-test-resource') {
  node('some-label') {
    echo 'Running with lock and node'
  }
}
```

See also [Node dependent resources](lock-nodes.md) for more scripted examples.

## See also

- [Lock specific stages](lock-specific-stages.md)
- [Locking multiple stages in declarative pipeline](locking-multiple-stages-in-declarative-pipeline.md)
- [Node dependent resources](lock-nodes.md)
