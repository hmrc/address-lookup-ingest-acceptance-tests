#!/usr/bin/env groovy
pipeline {
  agent {
      label 'commonagent'
  }

  stages {
    stage('Run acceptance tests') {
      steps {
        ansiColor('xterm') {
        	sh('sbt test')
        }
      }
    }
  }
}
