#!/usr/bin/env groovy

def podOpts() {
    return [
        domain: 'test.domain.net',
        region: 'us-east-4',

        dockerImage: 'docker',
        dockerVersion: 'latest',

        helmImage: 'alpine/helm',
        helmVersion: '2.12.3',

        jnlpImage: 'jenkins/jnlp-slave',
        jnlpReqCpu: '200m',
        jnlpReqMem: '256Mi',
        jnlpResCpu: '300m',
        jnlpResMem: '512Mi',
        jnlpVersion: 'latest-jdk11',
        jnlpWorkDir: '/home/jenkins',

        pushGitTags: false,
    ]
}
