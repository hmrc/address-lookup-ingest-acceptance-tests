#!/usr/bin/env groovy

def account_ids = [
        integration  : "710491386758"
]

def assume_roles = [
        integration  : "RoleJenkinsInfraBuild"
]

def generateAWSCredentials(String account_id, String role) {
  """set +x
    |export ROLE_ARN=arn:aws:iam::${account_id}:role/${role}
    |export AWS_DEFAULT_REGION=eu-west-2
    |set -x"""
}

pipeline {
  agent {
      label 'commonagent'
  }

  stages {
    stage('Run acceptance tests') {
      steps {
        ansiColor('xterm') {
          def account_id = account_ids["integration"]
          def role = assume_roles["integration"]

          sh(script: """${generateAWSCredentials(account_id, role)}
                     sbt test""")
        }
      }
    }
  }
}