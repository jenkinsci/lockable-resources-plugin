## Lock with allocation timeout

By default, the `lock` step waits indefinitely until the requested resource becomes available.
With `timeoutForAllocateResource` you can set a maximum wait time — if the resource is not
acquired within that period, the build fails immediately instead of blocking the queue forever.

This is useful when:
- You prefer a fast failure over an indefinitely blocked pipeline
- You want to detect resource starvation early
- Your CI/CD has SLAs that cap how long a job may wait

### Pipeline (scripted)

```groovy
node {
    // Wait up to 5 minutes for the resource, then fail
    lock(resource: 'my-printer', timeoutForAllocateResource: 5, timeoutUnit: 'MINUTES') {
        echo "Printer locked, printing ..."
    }
}
```

### Pipeline (declarative)

```groovy
pipeline {
    agent any
    stages {
        stage('Deploy') {
            options {
                lock(resource: 'staging-env', timeoutForAllocateResource: 10, timeoutUnit: 'MINUTES')
            }
            steps {
                echo "Deploying to staging ..."
            }
        }
    }
}
```

### Label-based locking with timeout

```groovy
pipeline {
    agent any
    stages {
        stage('Test') {
            steps {
                lock(label: 'phone', quantity: 1, variable: 'PHONE',
                     timeoutForAllocateResource: 2, timeoutUnit: 'MINUTES') {
                    echo "Running tests on ${env.PHONE}"
                }
            }
        }
    }
}
```

### Freestyle jobs

In a freestyle job configuration, go to **This build requires lockable resources** and set:
- **Lock wait timeout**: the maximum time to wait (e.g. `5`)
- **Timeout unit**: `SECONDS`, `MINUTES`, or `HOURS`

If the resource is not available within the configured timeout, the build is automatically
removed from the Jenkins queue.

### Timeout values

| `timeoutUnit` | Description |
|---------------|-------------|
| `SECONDS`     | Timeout in seconds |
| `MINUTES`     | Timeout in minutes (default) |
| `HOURS`       | Timeout in hours |

Setting `timeoutForAllocateResource: 0` (the default) disables the timeout — the build
waits indefinitely, which preserves the original behaviour.
