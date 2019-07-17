#!/usr/bin/env groovy

def podOpts() {
    return [
        continuous: 'delivery', // vs deployment
        domain: 'test.domain.net',
        region: 'us-east-4',
        master: 'master',

        dockerImage: 'docker',
        dockerVersion: 'latest',

        helmImage: 'alpine/helm',
        helmOverride: '',
        helmVersion: '2.12.3',

        kubeImage: 'ikenticus/kubectl',
        kubeVersion: 'latest',

        jnlpImage: 'jenkins/jnlp-slave',
        jnlpVersion: 'latest-jdk11',
        jnlpWorkDir: '/home/jenkins',

        podReqCpu: '200m',
        podReqMem: '256Mi',
        podResCpu: '300m',
        podResMem: '512Mi',

        pushGitTags: false,
    ]
}
