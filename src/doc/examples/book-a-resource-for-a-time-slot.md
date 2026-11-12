# Book a resource for a time slot

Sometimes a person, not a job, needs a resource for a fixed time. For example a
tester wants a lab device from 10:00 to 14:00 for manual testing and no build may
take it in the meantime.

There is no reservation calendar in the plugin, but the same effect is achieved
with two small scheduled jobs: one disables the resource at the start of the
slot, the other enables it again at the end. See *Update resource properties* in the
[README](../../../README.md#update-resource-properties) for the `updateLock` step.

## Start of the slot

A job with a cron trigger at the start time disables the resource. The cause is
shown on the *Lockable Resources* page and in the log of every build that waits
for the resource.

```groovy
pipeline {
  agent none
  triggers {
    // Every weekday at 10:00
    cron('0 10 * * 1-5')
  }
  stages {
    stage('Book device1') {
      steps {
        updateLock(resource: 'device1', disabledCause: 'Booked by Alice until 14:00')
      }
    }
  }
}
```

## End of the slot

A second job with a cron trigger at the end time enables the resource again.

```groovy
pipeline {
  agent none
  triggers {
    // Every weekday at 14:00
    cron('0 14 * * 1-5')
  }
  stages {
    stage('Release device1') {
      steps {
        updateLock(resource: 'device1', disabledCause: null)
      }
    }
  }
}
```

## What happens

- A build that already holds `device1` when the slot starts keeps it. Disabling
  never takes a resource away, so the slot effectively begins when that build
  releases the resource.
- New `lock()` requests for `device1`, by name or by label, wait as if the
  resource were busy. Their log shows why:

  ```text
  The resource [device1] is disabled: Booked by Alice until 14:00
  ```

- When the resource is enabled again, waiting builds continue.

If the end time slips, run the second job by hand or press **Enable** on the
*Lockable Resources* page.

Note that a Pipeline `triggers` block takes effect after the job has run once,
so run both jobs manually after creating them.
