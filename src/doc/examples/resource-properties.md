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
| `{variable}0` | Name of the first locked resource |
| `{variable}0_{PROPERTY_NAME}` | Value of that resource's property |
| `{variable}1` | Name of the second locked resource (if any) |
| `{variable}1_{PROPERTY_NAME}` | Value of the second resource's property |

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
