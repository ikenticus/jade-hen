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

def checkReleaseName(prefix, suffix) {
    name = prefix;
    parts = suffix.tokenize('-');
    for (p = 0; p <= parts.size(); p++) {
        name = "${prefix}-" + parts[0..-1].join('-');
        if (name.size() <= 53) return name;
        parts = parts[0..-2];
    }
    return name;
}
