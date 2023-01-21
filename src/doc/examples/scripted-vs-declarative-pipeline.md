# Scripted vs declarative pipeline

## Declarative

``` groovy
pipeline {
    agent any

    stages {
        stage("Build") {
            steps {
                lock(label: 'printer', quantity: 1) {
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
