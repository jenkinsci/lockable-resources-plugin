# Unlock a resource via LockableResourcesManager

The `lock` step holds a resource until its block finishes. Sometimes you need
to release it sooner — for example when one `parallel` branch fails and you
do not want `failFast` to abort the others, but you also do not want to keep
the lock until those branches complete.

Call `LockableResourcesManager` directly. This is a scripted-pipeline /
Script Console API, not a Pipeline step.

> **Risk:** after an early unlock, another build can acquire the same
> resource while *this* build is still running. Any remaining parallel
> branch that still uses the resource is then racing with that other
> build. Only do this when the remaining work no longer needs exclusive
> access. Unlocking twice is safe: when the `lock` block later ends, the
> step calls `unlockNames` again and skips resources this build no longer
> holds.

## Scripted pipeline

```groovy
import org.jenkins.plugins.lockableresources.LockableResourcesManager

lock(resource: 'mylock', variable: 'LOCKED') {
  parallel(
    foo: {
      // stuff_for_foo
    },
    bar: {
      try {
        // stuff_for_bar
      } catch (err) {
        def names = env.LOCKED.split(',').collect { it.trim() }.findAll { it }
        LockableResourcesManager.get().unlockNames(names, currentBuild.rawBuild)
        throw err
      }
    },
    failFast: false
  )
}
```

`unlockNames` takes the resource names and the `Run` that currently holds
them (`currentBuild.rawBuild` in a Pipeline). You can also pass the
`LockableResource` objects:

```groovy
def lrm = LockableResourcesManager.get()
def lr = lrm.fromName('mylock')
if (lr != null && lr.isLocked()) {
  lrm.unlockResources([lr], lr.getBuild())
}
```

`lrm.unlockResources([lr])` (one argument) uses the first resource's
build for every name in the list.

## Script Console

From *Manage Jenkins* → *Script Console* (or a Groovy Postbuild / shared
library), the same manager unlocks a stuck resource:

```groovy
def lrm = org.jenkins.plugins.lockableresources.LockableResourcesManager.get()
def lr = lrm.fromName('mylock')
if (lr == null) {
  println 'no such resource'
} else if (!lr.isLocked()) {
  println 'already free'
} else {
  lrm.unlockResources([lr], lr.getBuild())
  println "unlocked ${lr.name}"
}
```

Prefer the Lockable Resources UI **Unlock** action when you are not
inside a running Pipeline. The API above is the programmatic equivalent.
