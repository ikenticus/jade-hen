#!/usr/bin/env groovy

def podOpts() {
    return [
        domain: 'test.domain.net',
        region: 'us-east-4',

        dockerImage: 'docker',
        dockerVersion: 'latest',

        helmImage: 'alpine/helm',
        helmVersion: '2.12.3',

        jnlpImage: 'jorgeacetozi/jenkins-slave-kubectl',
        jnlpVersion: 'latest',

        /*
            jnlpImage: 'jenkins/jnlp-slave',
            jnlpVersion: 'latest-jdk11',
         */

        jnlpReqCpu: '200m',
        jnlpReqMem: '256Mi',
        jnlpResCpu: '300m',
        jnlpResMem: '512Mi',
        jnlpWorkDir: '/home/jenkins',

        pushGitTags: false,
    ]
}
