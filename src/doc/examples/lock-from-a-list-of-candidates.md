## Lock from a list of candidates in a given order

Sometimes a label is not enough: you want to leave some resources out, or prefer some of them
over the others. `findLockableResources` returns the resources matching a label, so the list can
be filtered and ordered with normal Pipeline code. `lock(candidates: ...)` then takes the
resources in that order.

With the following resources created:

| Name    | Labels                        | Description         |
|---------|-------------------------------|---------------------|
| debian1 | os:Debian nwcard:1            |                     |
| debian2 | os:Debian nwcard:1 nwcard:2   | Test-agent in lab 2 |
| debian3 | os:Debian nwcard:1 nwcard:2   |                     |
| debian4 | os:Debian nwcard:1            |                     |

The following scripted pipeline skips the test agent, prefers resources that also have a second
network card, and locks the first two free ones:

```groovy
def preferred = findLockableResources(label: 'os:Debian && nwcard:1')
    .findAll { r -> !r.description.contains('Test-agent') }
def sorted = preferred.findAll { r -> r.labels.contains('nwcard:2') } +
             preferred.findAll { r -> !r.labels.contains('nwcard:2') }

lock(candidates: sorted*.name, quantity: 2, variable: 'LOCKED') {
    echo 'Locked: ' + env.LOCKED   // debian3,debian1 when all are free
}
```

`findLockableResources` returns one map per resource with `name`, `labels`, `group`,
`description`, `note` and `properties` (property name to value). Build the order with `findAll`
as above: `list.sort { }` fails in a sandboxed Pipeline.

### What happens with the list

- With `quantity`, the candidates are tried in list order and the first free ones are locked. If
  `debian3` is busy, the example above gets `debian1` and `debian4`. Names that do not exist are
  skipped with a line in the build log.
- Without `quantity`, all listed resources are locked:

  ```groovy
  lock(candidates: ['debian1', 'debian4']) {
      // both are locked
  }
  ```

  A name that does not exist is created as an ephemeral resource when ephemeral resources are
  allowed. Otherwise it is skipped with a line in the build log, and the step fails when none of
  the listed resources exist.
- `candidates` also works in `extra` entries, and together with `group` and
  `resourceSelectStrategy: 'random'`.
- `candidates` cannot be combined with `resource` or `label` in the same entry, and it is not
  supported for remote locks (`serverId`).
