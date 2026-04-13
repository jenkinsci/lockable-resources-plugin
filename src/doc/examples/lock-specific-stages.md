# Lock Specific Stages

For long-running builds where a resource is only needed for part of the pipeline,
you can lock individual stages instead of the entire build. This allows other
jobs to use the resource while your build performs tasks that don't require it.

## Lock a single stage

Use the `options` block to lock a resource for just one stage:

```groovy
pipeline {
  agent any
  stages {
    stage('Build') {
      steps {
        echo 'Building for 27 minutes...'
        echo 'Resource foo is not locked'
      }
    }
    stage('Deploy') {
      options {
        lock(label: 'foo', quantity: 1)
      }
      steps {
        echo 'Deploying for 3 minutes...'
        echo 'Resource foo is locked'
      }
    }
    stage('Verify') {
      steps {
        echo 'Verifying...'
        echo 'Resource foo is not locked anymore'
      }
    }
  }
}
```

## Lock multiple consecutive stages

To lock a resource across multiple stages, nest them inside a parent stage
with the lock option:

```groovy
pipeline {
  agent any
  stages {
    stage('Build') {
      steps {
        echo 'Building...'
        echo 'Resource foo is not locked'
      }
    }
    stage('Deploy and Test') {
      options {
        lock(label: 'foo', quantity: 1)
      }
      stages {
        stage('Deploy') {
          steps {
            echo 'Deploying...'
            echo 'Resource foo is locked'
          }
        }
        stage('Integration Test') {
          steps {
            echo 'Testing...'
            echo 'Resource foo is still locked'
          }
        }
      }
    }
    stage('Cleanup') {
      steps {
        echo 'Cleaning up...'
        echo 'Resource foo is not locked anymore'
      }
    }
  }
}
```

## When to use this pattern

This pattern is useful when:

- Your build has a long preparation phase that doesn't need the locked resource
- You want to maximize resource utilization across multiple jobs
- Only specific stages (like deployment or testing) require exclusive access

## See also

- [Locking multiple stages in declarative pipeline](locking-multiple-stages-in-declarative-pipeline.md)
