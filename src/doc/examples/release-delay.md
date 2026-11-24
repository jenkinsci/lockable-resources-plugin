## Keep a resource locked for a while after the lock block (release delay)

Some resources need a cooldown before the next build may use them. For example, a license server
that only gives a license back a few minutes after it was used, or a device that needs time to
reboot after a test run.

`onReleaseDelay` and `onCancelDelay` keep the resources locked for a number of **seconds** after
the `lock` block is over:

- `onReleaseDelay`: seconds the resources stay locked after the block ends normally or fails.
- `onCancelDelay`: seconds the resources stay locked after the build is aborted, including by
  `timeout` or a `failFast` parallel branch.

Both default to `0`, which releases the resources right away.

The pipeline does not wait for the delay. It continues with the steps after the block, while the
resources stay locked in the background. When the delay is over, they are released and the next
waiting build gets them.

### License cooldown

```groovy
lock(resource: 'licenseServer1', onReleaseDelay: 300) {
    sh './run-licensed-tool.sh'
}
// runs right away; licenseServer1 stays locked for 300 more seconds (5 minutes)
echo 'Tool finished'
```

### Different delays for success and abort

A device that is left in an unknown state when a build is aborted gets a longer cooldown than one
that finished its test run:

```groovy
lock(resource: 'test-device-1', onReleaseDelay: 30, onCancelDelay: 600) {
    timeout(time: 20, unit: 'MINUTES') {
        sh './flash-and-test.sh test-device-1'
    }
}
```

- The block ends normally or `sh` fails: the device stays locked for 30 seconds.
- The build is aborted, or `timeout` stops the block after 20 minutes: the device stays locked for
  600 seconds (10 minutes). `timeout` and a `failFast` parallel branch stop the block the same way as
  an abort, so `onCancelDelay` applies to them.

### Declarative pipeline

```groovy
pipeline {
    agent any
    stages {
        stage('Licensed build') {
            options {
                lock(resource: 'licenseServer1', onReleaseDelay: 300, onCancelDelay: 60)
            }
            steps {
                sh './run-licensed-tool.sh'
            }
        }
    }
}
```

### Shared holds (`shareId`)

When several blocks share a hold with `shareId`, the delay starts only when the **last** of them
ends, that is when the resource is actually freed. A block that ends while others still hold the
resource does not start a delay. The delay of that last block is used.

```groovy
lock(resource: 'test-env', shareId: 'suite', onReleaseDelay: 120) {
    parallel(
        a: { lock(resource: 'test-env', shareId: 'suite', onReleaseDelay: 120) { sh './suite-a.sh' } },
        b: { lock(resource: 'test-env', shareId: 'suite', onReleaseDelay: 120) { sh './suite-b.sh' } }
    )
}
// the 120 seconds start when the outer block ends, not when branch a or b ends
```

### Good to know

- The resources page shows the resource as locked by the build until the release time.
- The pending release survives a Jenkins restart.
- **Unlock** or **Reset** on the resources page ends the delay right away.
- Not supported for remote locks (`serverId`): the step fails with an error before anything is sent
  to the remote server.
