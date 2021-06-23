#!/usr/bin/env groovy

def account_ids = [
        integration  : "710491386758"
]

def assume_roles = [
        integration  : "RoleJenkinsInfraBuild"
]

pipeline {
  agent {
      label 'commonagent'
  }

  stages {
    stage('Run acceptance tests') {
      steps {
        ansiColor('xterm') {
          sh(script: """ROLE_ARN=arn:aws:iam::${account_ids["integration"]}:role/${assume_roles["integration"]} AWS_DEFAULT_REGION=eu-west-2 sbt test""")
        }
      }
    }
  }
}
