# Resource Properties

Resources can have custom **properties** (name:value pairs) that are exposed
as environment variables when the resource is locked.

## Defining properties

Properties can be added to a resource via:

- **Web UI** — Manage Jenkins → Lockable Resources → edit a resource → add properties
- **JCasC** (Jenkins Configuration as Code):

```yaml
unclassified:
  lockableResourcesManager:
    resources:
      - name: "staging-server"
        properties:
          - name: "HOST"
            value: "192.168.1.10"
          - name: "PORT"
            value: "8080"
```

## Accessing properties in a pipeline

Properties are exposed as environment variables **only when the `variable`
parameter is specified** in the `lock()` step.

### Naming pattern

| Variable | Value |
|----------|-------|
| `{variable}` | Comma-separated list of all locked resource names |
| `{variable}_LABELS` | Space-separated labels of the **first** locked resource (un-indexed convenience alias) |
| `{variable}_{PROPERTY_NAME}` | Value of the **first** locked resource's property — un-indexed convenience alias, always present |
| `{variable}0` | Name of the first locked resource |
| `{variable}0_LABELS` | Space-separated labels of the first locked resource |
| `{variable}0_{PROPERTY_NAME}` | Value of that resource's property |
| `{variable}1` | Name of the second locked resource (if any) |
| `{variable}1_LABELS` | Space-separated labels of the second locked resource |
| `{variable}1_{PROPERTY_NAME}` | Value of the second resource's property |

The properties of the first acquired resource are exposed both with the `0` index
(`{variable}0_{PROPERTY_NAME}`) and without it (`{variable}_{PROPERTY_NAME}`). The un-indexed form
is a convenience alias for the first acquired resource and is always present, even when multiple
resources are locked.

### Accessing resource labels

Each indexed resource also exposes its labels through `{variable}{index}_LABELS`,
for both local and remote locks. The un-indexed `{variable}_LABELS` alias always
refers to the first acquired resource, even when multiple resources are locked.
If that resource has no labels, Jenkins leaves its label variables unset (`null`),
including the alias. These variables are available only inside the lock block
and require the `variable` parameter. The `_LABELS` suffix is reserved for resource
labels in both forms and takes precedence over a custom property named `LABELS`.

For a resource named `staging-server` with labels `staging linux`:

```groovy
lock(resource: 'staging-server', variable: 'LOCKED') {
  echo "Labels: ${env.LOCKED0_LABELS}" // staging linux
  echo "Labels: ${env.LOCKED_LABELS}"  // staging linux (first-resource alias)
}
```

#### Compatibility warning

The label variables introduce new environment names. An existing custom property
named `LABELS` no longer supplies `{variable}_LABELS` or `{variable}0_LABELS`;
rename that property if its value is needed.

Resource names such as `PLC` and `PLC_LABELS` can still coexist. A collision occurs
when environment prefixes overlap: a lock using `variable: 'PLC'` sets `PLC_LABELS`,
potentially shadowing an enclosing lock using `variable: 'PLC_LABELS'` or another
environment variable with that name. The enclosing value is restored when the
inner lock block ends. Choose distinct prefixes such as `MAIN_RESOURCE` and
`OTHER_RESOURCE` to avoid this change in behavior.

### Example: Read properties after locking by name

```groovy
pipeline {
  agent any
  stages {
    stage('Deploy') {
      options {
        lock(resource: 'staging-server', variable: 'LOCKED')
      }
      steps {
        echo "Resource: ${env.LOCKED0}"          // staging-server
        echo "Host: ${env.LOCKED0_HOST}"         // 192.168.1.10
        echo "Port: ${env.LOCKED0_PORT}"         // 8080
        // un-indexed aliases — same values, refer to the first locked resource
        echo "Host: ${env.LOCKED_HOST}"          // 192.168.1.10
        echo "Port: ${env.LOCKED_PORT}"          // 8080
      }
    }
  }
}
```

### Example: Lock by label and read properties

```groovy
pipeline {
  agent any
  stages {
    stage('Test') {
      options {
        lock(label: 'gpu', quantity: 1, variable: 'GPU')
      }
      steps {
        echo "Got: ${env.GPU0}"
        echo "GPU model: ${env.GPU0_MODEL}"
        echo "GPU model: ${env.GPU_MODEL}"   // un-indexed alias, same value
      }
    }
  }
}
```

## Filtering resources by properties

Use a `resourceMatchScript` to lock only resources whose properties match
specific criteria:

```groovy
lock(extra: [
    [$class: 'LockableResourcesStruct',
     resourceMatchScript: [
         $class: 'SecureGroovyScript',
         script: '''
           resourceInstance.properties.any {
             it.name == "ENV" && it.value == "staging"
           }
         ''',
         sandbox: true
     ],
     resourceNumber: '1'
    ]
]) {
  echo "Got a staging resource: ${env.LOCKED_RESOURCE0}"
}
```

## Common pitfalls

1. **Missing `variable` parameter** — without it, no environment variables are
   created. This is the most common reason properties appear to be `null`.
2. **Property name is case-sensitive** — if the property is named `host`, the
   env var is `LOCKED0_host`, not `LOCKED0_HOST`.
3. **Properties are only available inside the lock block** — they cannot be
   accessed after the lock is released.
