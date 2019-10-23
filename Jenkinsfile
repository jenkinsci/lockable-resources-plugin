pipeline {
    agent any
    tools {
        maven 'maven'
        jdk 'java_openjdk'
    }
    stages {
        stage ('Initialize') {
            steps {
                sh '''
                    echo "PATH = ${PATH}"
                    echo "M2_HOME = ${M2_HOME}"
                '''
            }
        }
        stage ('Build') {
            steps {
                sh 'mvn clean install'
            }
            post {
                success {
                    junit '**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml'
                }
            }
        }
    }
}
