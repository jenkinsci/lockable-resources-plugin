# Timeout Inside Lock

When a pipeline uses both `timeout` and `lock`, the placement of `timeout`
determines whether queue wait time counts against the deadline.

## Problem

If `timeout` wraps the entire pipeline or stage, the clock starts before the
lock is acquired. A job that waits a long time in the queue may time out
before it gets a chance to run:

```groovy
pipeline {
  agent any
  options {
    // Clock starts immediately — includes queue wait time!
    timeout(time: 5, unit: 'HOURS')
  }
  stages {
    stage('Deploy') {
      steps {
        lock('my-resource') {
          echo 'Deploying...'
        }
      }
    }
  }
}
```

## Solution

Place `timeout` **inside** `lock` so the countdown begins only after the
resource has been acquired:

```groovy
pipeline {
  agent any
  stages {
    stage('Deploy') {
      steps {
        lock('my-resource') {
          timeout(time: 5, unit: 'HOURS') {
            echo 'Deploying...'
          }
        }
      }
    }
  }
}
```

This way a job can wait in the queue as long as necessary without the
timeout expiring prematurely.

## Stage-level variant

The same pattern works with `options` at the stage level:

```groovy
pipeline {
  agent any
  stages {
    stage('Deploy') {
      options {
        lock('my-resource')
      }
      steps {
        timeout(time: 5, unit: 'HOURS') {
          echo 'Deploying...'
        }
      }
    }
  }
}
```
