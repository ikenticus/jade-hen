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

        pushGitTags: false,
        skipAskProd: false,

        testImage: 'alpine',
        testVersion: 'latest',
    ]
}
