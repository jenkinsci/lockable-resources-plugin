# Dynamic Resource Pool Expansion

This example demonstrates how waiting jobs can automatically acquire newly added resources.

## Use Case

You have a pool of resources (e.g., test devices) labeled `test-device`. All devices are currently in use by running jobs. A new job starts and waits for a `test-device`. When you add a new device to the pool, the waiting job should automatically acquire it without needing to be restarted.

## Pipeline Example

### Job waiting for a resource

```groovy
pipeline {
    agent any
    stages {
        stage('Acquire Device') {
            steps {
                lock(label: 'test-device', quantity: 1, variable: 'DEVICE') {
                    echo "Acquired device: ${env.DEVICE}"
                    // Use the device
                    sh 'run-tests.sh'
                }
            }
        }
    }
}
```

### Adding a new resource via pipeline step

While the job is waiting, you can add a new resource via a management job or the Script Console using the `updateLock` step:

```groovy
// In a pipeline job
updateLock(resource: 'new-test-device-5', addLabels: 'test-device')
```

Or via Script Console:

```groovy
import org.jenkins.plugins.lockableresources.LockableResourcesManager

def manager = LockableResourcesManager.get()
manager.createResourceWithLabel('new-test-device-5', 'test-device')
```

The waiting job will automatically acquire `new-test-device-5` once it is added.

## Freestyle Job Example

For freestyle jobs configured with **Required Resources** (label: `test-device`), the same behavior applies. When a new resource with the matching label is added, the Jenkins queue is notified and the waiting freestyle job will be dispatched.

## Limitations

> **Important:** Modifying labels on existing resources does NOT trigger re-evaluation.

Only **adding new resources** triggers waiting jobs to re-evaluate their resource requirements. If you:
- Change labels on an existing resource (e.g., add `test-device` label to an existing resource)
- The waiting jobs will **not** be notified

To work around this limitation, you can manually trigger queue refresh via Script Console:

```groovy
import org.jenkins.plugins.lockableresources.LockableResourcesManager

LockableResourcesManager.get().refreshQueue()
```

This will invalidate the cached candidates and notify both pipeline and freestyle jobs to re-evaluate available resources.

## Related

- [JENKINS-46744](https://issues.jenkins.io/browse/JENKINS-46744) - Original issue requesting this behavior
- [GitHub #892](https://github.com/jenkinsci/lockable-resources-plugin/issues/892) - Implementation tracking issue
