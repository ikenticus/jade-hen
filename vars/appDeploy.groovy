#!/usr/bin/env groovy

def call(Map args) {
    properties([
        disableConcurrentBuilds(),
        parameters([
            choice(description: 'Namespace',
                   name: 'namespace',
                   choices: [
                        'test',
                        'staging',
                        'production',
                    ]
            ),
            string(description: 'Version should match git tag as well as image tag, for example: v1.2.3\nfor test use branch slug',
                   name: 'version',
                   defaultValue: ''
            ),
        ])
    ])

    def opts = args.deployOpts ? args.deployOpts.call(args) : _deployOpts(args)
    currentBuild.description = "${opts.namespace}: ${opts.version}"

    podTemplate(label: 'jenkins-slave-helm', containers: [
        containerTemplate(name: 'jnlp', image: "${opts.jnlpImage}:${opts.jnlpVersion}",
                            args: '${computer.jnlpmac} ${computer.name}', workingDir: "${opts.jnlpWorkDir}",
                            resourceRequestCpu: "${opts.podReqCpu}", resourceLimitCpu: "${opts.podResCpu}",
                            resourceRequestMemory: "${opts.podReqMem}", resourceLimitMemory: "${opts.podResMem}"),
        containerTemplate(name: 'helm', image: "${opts.helmImage}:${opts.helmVersion}", command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'kube', image: "${opts.kubeImage}:${opts.kubeVersion}", command: 'cat', ttyEnabled: true),
    ]) {
        node ('jenkins-slave-helm') {
            stage('Checkout') {
                try {
                    deleteDir()
                    def branch = opts.master
                    if (opts.namespace == 'test') {
                        branch = opts.version
                    }
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: "${branch}"]],
                        userRemoteConfigs: scm.userRemoteConfigs,
                        extensions: [[$class: 'CloneOption', noTags: false, shallow: false, depth: 0, reference: '']]
                    ])
                    sh 'cat ci/Jenkinsfile.deploy'
                } catch (e) {
                    notifySlack("ERROR git checkout: ${e}")
                    throw e
                }
            }

            stage('Deploy') {
                container('helm') {
                    try {
                        def override = "ci/helm/${opts.helmChart}/overrides/${opts.version}.yaml"
                        if (fileExists(override)) {
                            opts.helmOverride = "-f ${override}"
                        }
                        args.helmSetValues ? args.helmSetValues.call(opts) : _helmSetValues(opts)
                        def values = opts.helmValues.collect { key, value -> "${key}=${value}" }.join(',')
                        sh """
                            helm upgrade ${opts.helmRelease} ci/helm/${opts.helmChart} --recreate-pods \
                            --install --namespace ${opts.namespace} --set ${values} ${opts.helmOverride}
                        """
                        notifySlack('SUCCESS deploying app')
                    } catch (e) {
                        notifySlack("ERROR deploying app: ${e}")
                        println "Failed to install/upgrade helm chart: ${e.message}"
                        sh "helm status ${opts.helmRelease}"
                        sh "helm rollback ${opts.helmRelease} 0"
                        throw e
                    }
                }
            }

            stage('Post Tasks') {
                args.postDeploy ? args.postDeploy.call(opts) : _postDeploy(opts)
                if (opts.namespace == 'staging') {
                    timeout(time: 20, unit: 'HOURS') {
                        try {
                            input "Deploy ${opts.version} to Production?"
                            build job: env.JOB_BASE_NAME, wait: false, parameters: [
                                string(name: 'namespace', value: 'production'),
                                string(name: 'version', value: opts.version)
                            ]
                            println "Deployed ${opts.version} to Staging, approved for Production"
                        } catch (e) {
                            echo "Deployed ${opts.version} to Staging, skipped Production"
                        }
                    }
                }
            }
        }
    }
}

Map _deployOpts(Map args) {
    def opts = common.podOpts()

    def flag = 'test'
    def helmRelease = "${args.appName}-${args.namespace}"
    if (args.namespace == 'test') {
        helmRelease = "${args.appName}-${args.version}"
    } else {
        flag = 'live'
    }
    def fqdn = "${helmRelease}.${opts.region}.${opts.domain}"

    def helmArgs = args.helmValues ?: [:]
    def helmValues = [
        'image.flag': args.flag ?: flag,
        'image.tag': args.version,
        'ingress.hosts[0]': fqdn,
    ] << helmArgs

    def helmOverride = ''
    try {
        if (args.namespace != 'test') {
            helmOverride = "-f ci/helm/${args.appName}/overrides/${args.namespace}.yaml"
        }
    } catch (e) {
        println "Error overriding helm chart with ${args.namespace}"
    }

    return [
        master: args.master ?: opts.master,
        namespace: args.namespace ?: 'test',
        version: args.version,

        helmChart: args.appName,
        helmImage: args.helmImage ?: opts.helmImage,
        helmOverride: args.helmOverride ?: helmOverride,
        helmRelease: helmRelease,
        helmValues: helmValues,
        helmVersion: args.helmVersion ?: opts.helmVersion,

        jnlpImage: args.jnlpImage ?: opts.jnlpImage,
        jnlpVersion: args.jnlpVersion ?: opts.jnlpVersion,
        jnlpWorkDir: args.jnlpWorkDir ?: opts.jnlpWorkDir,

        kubeImage: args.kubeImage ?: opts.kubeImage,
        kubeVersion: args.kubeVersion ?: opts.kubeVersion,

        podReqCpu: args.podReqCpu ?: opts.podReqCpu,
        podReqMem: args.podReqMem ?: opts.podReqMem,
        podResCpu: args.podResCpu ?: opts.podResCpu,
        podResMem: args.podResMem ?: opts.podResMem,
    ]
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
