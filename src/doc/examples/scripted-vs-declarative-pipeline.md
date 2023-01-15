# Scripted vs declarative pipeline

Due an historical reason is not possible to use exact same syntax in the declarative and scripted pipeline.
In declarative pipeline you must so far set **resource : null**.

## Declarative

``` groovy
pipeline {
    agent any

    stages {
        stage("Build") {
            steps {
                lock(label: 'printer', quantity: 1, resource : null) {
                    echo 'printer locked'
                }
            }
        }
    }
}
```

## Scripted

``` groovy
node() {
    stage("Build") {
        lock(label: 'printer', quantity: 1) {
            echo 'printer locked'
        }
    }
}
```
