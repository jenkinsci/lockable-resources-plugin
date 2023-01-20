## Locking a random free resource

In same cases, you want a random resource to be locked instead of always choosing the first one. For example,
if resources like accounts could get rate limited if they are used too often by your pipelines.

With the following resources created:

| Name      | Label   |
|-----------|---------|
| account_1 | account |
| account_2 | account |

You can pick a single random available resource like in the following declarative pipeline:

```groovy
pipeline {
    agent any
    stages {
        stage("Build") {
            steps {
                lock(label: "account", resourceSelectStrategy: 'random', resource: null, quantity: 1, variable: "account") {
                    echo "Using account " + env.account
                    // do your thing using the resource
                }
            }
        }
    }
}
```

The `quantity` can be changed to lock any amount of available resources with the given label. Not specifying the
quantity will lock all resources, but still randomize the order of resources in the numbered environment variable.

Not specifying `resourceSelectStrategy`, will fall back to the default behaviour of locking resources according to
their order in the lockable resources list. You can also explicitly configure the default strategy
with `resourceSelectStrategy: 'sequential'`.
