# Locking multiple stages in declarative pipeline

You can lock the entire job in the options block of the pipeline:


```groovy
pipeline {
options {
      lock 'lockable-resource'
    }

 agent any

    stages {
        stage('Build') {
            steps {
                sh 'make'
            }
        }
        stage('Test'){
            steps {
                sh 'make check'
                junit 'reports/**/*.xml'
            }
        }
        stage('Deploy') {
            steps {
                sh 'make publish'
            }
        }
    }
}
```
