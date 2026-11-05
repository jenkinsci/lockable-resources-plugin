## Share a lock with downstream builds and nested blocks

A `lock` block normally waits while any other block holds the resource, including another block of
the same build and a downstream build started from inside the lock. With `shareId`, the block joins
such a hold instead of waiting for it. The resource is released when the last block holding it ends.

A block joins a hold only when:

- the hold has the **same `shareId`**, and one of its holders is **this build** or one of its
  **upstream builds**, or
- the hold is an outer `lock` of **this build** without `shareId`.

In every other case it waits as usual.

### Upstream and downstream jobs

The upstream job holds the test environment and starts the downstream job with the `build` step.
Without `shareId` the downstream build would wait for `test-env`, and the upstream build would wait
for the downstream one: a deadlock.

```groovy
// Job 'release'
lock(resource: 'test-env', shareId: 'release') {
    sh './deploy.sh test-env'
    build job: 'integration-tests'
    build job: 'performance-tests'
}
```

```groovy
// Job 'integration-tests'
lock(resource: 'test-env', shareId: 'release') {
    sh './run-integration-tests.sh test-env'
}
```

```groovy
// Job 'performance-tests' (declarative)
pipeline {
    agent any
    stages {
        stage('Test') {
            options {
                lock(resource: 'test-env', shareId: 'release')
            }
            steps {
                sh './run-performance-tests.sh test-env'
            }
        }
    }
}
```

The downstream build joins because the `build` step gives it an upstream cause that points to the
`release` build, which holds `test-env`. The chain may be longer: a build started by
`integration-tests` joins too.

When `integration-tests` is started in any other way (by hand, by a timer, by an SCM change, or by
replaying or rebuilding a build), it is not a downstream build of the holder. It then waits until
`test-env` is released, and its console log says why it did not join. The same holds for an
unrelated build that uses the same `shareId`.

If the upstream build has already left its `lock` block when the downstream build asks, there is no
hold to join, and the downstream build locks the resource normally.

### Nested lock in the same build

A nested block with `shareId` joins an outer `lock` of the same resource in the same build, also
when the outer one has no `shareId`. This is useful when the inner block is in a shared library that
does not know whether its caller already holds the resource.

```groovy
lock(resource: 'test-env') {                  // outer lock, no shareId
    sh './prepare.sh test-env'
    lock(resource: 'test-env', shareId: 'lib') {   // joins the outer lock
        sh './run-library-step.sh test-env'
    }
    // test-env is still held here: the outer block holds it until it ends
}
```

A nested block **without** `shareId` still waits for the outer one, as before.

### Parallel branches of one build

Parallel branches of the same build with the same `shareId` run at the same time:

```groovy
parallel(
    unit: {
        lock(resource: 'test-env', shareId: 'tests') {
            sh './run-unit-tests.sh test-env'
        }
    },
    smoke: {
        lock(resource: 'test-env', shareId: 'tests') {   // does not wait for 'unit'
            sh './run-smoke-tests.sh test-env'
        }
    }
)
```

Branches with different share ids, or without one, take turns as before.

### Limitations

- **Named resources only.** `shareId` cannot be combined with `label` or `quantity`, nor with
  `extra` entries that use them, because the hold is matched resource by resource. Name each
  resource to share, for example `lock(resource: 'a', extra: [[resource: 'b']], shareId: 'x')`.
- **Local resources only.** `shareId` cannot be combined with `serverId`, and cannot be used on a
  controller that forwards its locks to a remote server (`forcedServerId`).
- **Unlock** or **Reset** on the resources page ends the hold for every holder.

### Where to see it

- The *Lockable Resources* page shows the first holder build of the resource and, below it,
  *shared as `<shareId>` with N more*. Click it to list the other holder builds.
- The *Lockable resources* page of a build logs `acquired` for the first holder, `joined` for a block
  that joined a hold, `left` for a block that ended while others still hold the resource, and
  `released` for the last holder. The share id of each block is shown next to it.
