# Pipeline Patterns

These examples assume a Scripted Pipeline context with the Lockable Resources
plugin installed. In Declarative Pipeline use steps in a `steps` block; place
general Groovy helper definitions in a shared library or an appropriate `script`
context. Confirm parameters with the installed Pipeline Snippet Generator.

## One Named Resource

```groovy
timeout(time: 20, unit: 'MINUTES') {
    lock(resource: 'staging-server') {
        echo 'Exclusive access to staging-server'
    }
}
```

The timeout includes acquisition wait and the lock body. Use the intended
configured name: a typo may select an ephemeral resource instead of the real one.

## One Interchangeable Device

Configure, for example, resources `printer-01` and `printer-02` with label
`office-printer`. Choose a positive quantity explicitly rather than accidentally
requesting every resource in the pool.

```groovy
lock(label: 'office-printer', quantity: 1, variable: 'LOCKED_PRINTER') {
    echo "Selected printer: ${env.LOCKED_PRINTER}"
    timeout(time: 5, unit: 'MINUTES') {
        echo 'Perform the printer-specific operation here'
    }
}
```

This timeout limits only the protected operation, not the queue wait. The resource
variable is scoped to the lock body. Replace the `echo` with the actual operation;
these examples do not contact hardware or run deployments.

## Shared-Library Wrapper

A possible `vars/withLockedDevice.groovy` entry point:

```groovy
def call(String resourceLabel, Closure body) {
    timeout(time: 20, unit: 'MINUTES') {
        lock(label: resourceLabel, quantity: 1, variable: 'LOCKED_DEVICE') {
            body(env.LOCKED_DEVICE)
        }
    }
}
```

After loading the user's configured shared library, call it in a Pipeline step
context:

```groovy
withLockedDevice('office-printer') { selectedDevice ->
    echo "Selected device: ${selectedDevice}"
}
```

Keep the closure inside the lock. Do not annotate this wrapper with `@NonCPS`:
it calls Pipeline steps and may suspend while waiting. The names shown are for
one non-nested invocation; choose distinct environment variable names for nested
helpers and define a consistent acquisition order to avoid deadlocks.

## Validation Scenarios

- With a free configured resource, verify entry into the protected block.
- Run two contenders against one resource; verify only one enters at a time.
- Abort the holder and verify the contender can proceed without manual unlocking.
- Reserve the resource administratively; verify the request waits or times out.
- If using `skipIfLocked`, assert the body is not executed when acquisition fails.
- Verify any selected-resource variable is consumed inside the protected block.

These are proposed validation scenarios, not tests executed by installing this
skill. Use disposable resources and obtain permission before running jobs.

## Sources and Version Limits

Reviewed source for this draft: lockable-resources-plugin revision `c62c844c`.

- [Configuration and Pipeline examples](https://github.com/jenkinsci/lockable-resources-plugin/blob/c62c844c/README.md)
- [Executor starvation](https://github.com/jenkinsci/lockable-resources-plugin/blob/c62c844c/src/doc/examples/avoiding-executor-starvation.md)
- [Timeout inside lock](https://github.com/jenkinsci/lockable-resources-plugin/blob/c62c844c/src/doc/examples/timeout-inside-lock.md)
- [Scripted and Declarative contexts](https://github.com/jenkinsci/lockable-resources-plugin/blob/c62c844c/src/doc/examples/scripted-vs-declarative-pipeline.md)

Do not infer a minimum released plugin version from a source revision. Prefer
documentation corresponding to the installed version and verify step syntax
there. The wrapper is an illustrative composition of documented steps, not an
official plugin API or a preinstalled shared-library step.
