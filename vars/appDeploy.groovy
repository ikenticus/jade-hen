#!/usr/bin/env groovy

def call(Map args) {
    properties([
        disableConcurrentBuilds(),
        parameters([
            string(description: 'Version should match git tag as well as image tag, for example: v1.2.3\nfor test use case-sensitive branch name',
                   name: 'version',
                   defaultValue: ''
            ),
            choice(description: 'Namespace',
                   name: 'namespace',
                   choices: [
                        'test',
                        'staging',
                        'production',
                    ]
            ),
            choice(description: 'Continuous D ?',
                   name: 'continuous',
                   choices: [
                        'delivery',
                        'deployment',
                    ]
            ),
            string(description: 'Name of the master branch for staging/production',
                   name: 'master',
                   defaultValue: 'master'
            ),
        ])
    ])

    def opts = args.deployOpts ? args.deployOpts.call(args) : _deployOpts(args)
    currentBuild.description = "${opts.namespace}: ${opts.version}"

    podTemplate(label: "jenkins-deploy-${opts.helmChart}", containers: [
        containerTemplate(name: 'helm', image: "${opts.helmImage}:${opts.helmVersion}", command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'kube', image: "${opts.kubeImage}:${opts.kubeVersion}", command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'test', image: "${opts.testImage}:${opts.testVersion}", command: 'cat', ttyEnabled: true),
    ]) {
        node ("jenkins-deploy-${opts.helmChart}") {
            stage('Checkout') {
                try {
                    deleteDir()
                    def branch = opts.master
                    if (opts.namespace == 'test') {
                        branch = opts.branch
                    }
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: "${branch}"]],
                        userRemoteConfigs: scm.userRemoteConfigs,
                        extensions: [[$class: 'CloneOption', noTags: false, shallow: false, depth: 0, reference: '']]
                    ])
                    //sh 'cat ci/Jenkinsfile.deploy'
                } catch (e) {
                    notifySlack("ERROR Checkout git ${opts.version}: ${e}")
                    throw e
                }
            }

            stage('Deploy') {
                container('helm') {
                    try {
                        args.helmSetOverrides ? args.helmSetOverrides.call(opts) : _helmSetOverrides(opts)
                        args.helmSetValues ? args.helmSetValues.call(opts) : _helmSetValues(opts)
                        def values = opts.helmValues.collect { key, value -> "${key}=${value}" }.join(',')
                        sh """
                            helm upgrade ${opts.helmRelease} ci/helm/${opts.helmChart} --recreate-pods \
                            --install --namespace ${opts.namespace} --set ${values} ${opts.helmOverride}
                        """
                        notifySlack("SUCCESS Deploying ${opts.version} to ${opts.namespace}: ${BUILD_URL}console\n :eye: https://${opts.fqdn}")
                    } catch (e) {
                        notifySlack("ERROR Deploying ${opts.version} to ${opts.namespace}: ${e} (${BUILD_URL}console)")
                        println "Failed to install/upgrade helm chart: ${e.message}"
                        sh "helm status ${opts.helmRelease}"
                        sh "helm rollback ${opts.helmRelease} 0"
                        throw e
                    }
                }
            }

            stage('Integration Tests') {
                container('test') {
                    try {
                        // Execute Integration Tests within "test" container since every coding language varies
                        args.testIntegration ? args.testIntegration.call(opts) : _testIntegration(opts)
                    } catch (e) {
                        notifySlack("ERROR Failed Integration Test(s) ${opts.version}: ${e}")
                        throw e
                    }
                }
            }

            stage('Post Tasks') {
                args.postDeploy ? args.postDeploy.call(opts) : _postDeploy(opts)
                if (opts.namespace == 'staging' && !opts.skipAskProd) {
                    timeout(time: 20, unit: 'HOURS') {
                        try {
                            if (opts.continuous == 'delivery') {
                                input "Deploy ${opts.version} to Production?"
                            }
                            build job: env.JOB_BASE_NAME, wait: false, parameters: [
                                string(name: 'master', value: opts.master),
                                string(name: 'version', value: opts.version),
                                string(name: 'namespace', value: 'production'),
                                string(name: 'continuous', value: opts.continuous)
                            ]
                            println "Deployed ${opts.version} to Staging, approved for Production"
                        } catch (e) {
                            echo "Deployed ${opts.version} to Staging, skipped Production"
                        }
                    }
                } else if (opts.namespace == 'staging' && opts.skipAskProd) {
                    echo "Deployed ${opts.version} to Staging. Manually deploy to Production when ready."
                } else {
                    echo "Deployed ${opts.version} to ${opts.namespace}."
                }
            }
        }
    }
}

Map _deployOpts(Map args) {
    def opts = common.podOpts()
    def version = args.version.replace('_', '-').toLowerCase()

    def flagRepo = 'test'
    def helmRelease = "${args.appName}-${args.namespace}"
    if (args.continuous == 'deployment') {
        // append version to release to avoid deployment overwrite
        helmRelease += "-${version}".replace('.', 'o')
    }
    if (args.namespace == 'test') {
        helmRelease = common.checkReleaseName(args.appName, version)
    } else {
        flagRepo = 'live'
    }
    def fqdn = "${helmRelease}.${opts.region}.${opts.domain}"

    def helmArgs = args.helmValues ?: [:]
    def helmValues = [
        'image.flag': args.flagRepo ?: flagRepo,
        'image.tag': version,
        'ingress.hosts[0]': fqdn,
    ] << helmArgs

    return [
        fqdn: fqdn,
        branch: args.version,
        continuous: args.continuous ?: opts.continuous,
        master: args.master ?: opts.master,
        namespace: args.namespace ?: 'test',
        version: version,

        helmChart: args.appName,
        helmImage: args.helmImage ?: opts.helmImage,
        helmOverride: args.helmOverride ?: opts.helmOverride,
        helmRelease: helmRelease,
        helmValues: helmValues,
        helmVersion: args.helmVersion ?: opts.helmVersion,

        kubeImage: args.kubeImage ?: opts.kubeImage,
        kubeVersion: args.kubeVersion ?: opts.kubeVersion,

        skipAskProd: args.skipAskProd ?: opts.skipAskProd,

        testImage: args.testImage ?: opts.testImage,
        testVersion: args.testVersion ?: opts.testVersion,
    ]
}

def _helmSetOverrides(Map opts) {
    def override = "ci/helm/${opts.helmChart}/overrides/"
    println "Checking ${override} for ${opts.namespace} and ${opts.version}"
    if (fileExists(override + "${opts.namespace}.yaml")) {
        opts.helmOverride = "-f ${override}${opts.namespace}.yaml"
        println "Applying namespace override: ${opts.helmOverride}"
    } else if (fileExists(override + "${opts.version}.yaml")) {
        opts.helmOverride = "-f ${override}${opts.version}.yaml"
        println "Applying version override: ${opts.helmOverride}"
    }
}

def _helmSetValues(Map opts) {
    def helmValues = [
        'dummy.example': 'test',
    ] << opts.helmValues
    opts.helmValues = helmValues
}

def _postDeploy(Map opts) {
    println 'Use Closures to handle Post Deploy Tasks'
    println 'i.e. Front End may need to handle Cache Bust/Seed'
    println 'i.e. Back End may need to handle Database updates'
    println 'May want to run integration tests after deploy also'
}

def _testIntegration(Map opts) {
    if (fileExists('test/integration')) {
        println 'Running Integration Tests'
    } else {
        println 'No Integration Tests Found'
    }
}
