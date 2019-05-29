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
                            resourceRequestCpu: "${opts.jnlpReqCpu}", resourceLimitCpu: "${opts.jnlpResCpu}",
                            resourceRequestMemory: "${opts.jnlpReqMem}", resourceLimitMemory: "${opts.jnlpResMem}"),
        containerTemplate(name: 'helm', image: "${opts.helmImage}:${opts.helmVersion}", command: 'cat', ttyEnabled: true),
    ]) {
        node ('jenkins-slave-helm') {
            stage('Checkout') {
                deleteDir()
                def branch = 'master'
                if (opts.namespace == 'test') {
                    branch = opts.version
                }
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: "${branch}"]],
                    userRemoteConfigs: scm.userRemoteConfigs,
                    extensions: [[$class: 'CloneOption', noTags: false, shallow: false, depth: 0, reference: '']]
                ])
            }

            stage('Deploy') {
                container('helm') {
                    try {
                        def override = "ci/helm/${opts.helmChart}/overrides/${opts.version}.yaml"
                        if (fileExists(override)) {
                            opts.helmOverride = "-f ${override}"
                        }
                        def values = opts.helmValues.collect { key, value -> "${key}=${value}" }.join(',')
                        sh """
                            helm upgrade ${opts.helmRelease} ci/helm/${opts.helmChart} --install \
                            --namespace ${opts.namespace} --set ${values} ${opts.helmOverride}
                        """
                    } catch (e) {
                        println "Failed to install/upgrade helm chart: ${e.message}"
                        sh "helm status ${opts.helmRelease}"
                        sh "helm rollback ${opts.helmRelease} 0"
                        throw e
                    }
                }
                if (opts.namespace == 'staging') {
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

            stage('Post Tasks') {
                args.postDeploy ? args.postDeploy.call(opts) : _postDeploy(opts)
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
    if (args.namespace == 'production') {
        helmOverride = "-f ci/helm/${args.appName}/overrides/production.yaml"
    }

    return [
        namespace: args.namespace ?: 'test',
        version: args.version,

        helmChart: args.appName,
        helmImage: args.helmImage ?: opts.helmImage,
        helmOverride: args.helmOverride ?: helmOverride,
        helmRelease: helmRelease,
        helmValues: helmValues,
        helmVersion: args.helmVersion ?: opts.helmVersion,

        jnlpImage: args.jnlpImage ?: opts.jnlpImage,
        jnlpReqCpu: args.jnlpReqCpu ?: opts.jnlpReqCpu,
        jnlpReqMem: args.jnlpReqMem ?: opts.jnlpReqMem,
        jnlpResCpu: args.jnlpResCpu ?: opts.jnlpResCpu,
        jnlpResMem: args.jnlpResMem ?: opts.jnlpResMem,
        jnlpVersion: args.jnlpVersion ?: opts.jnlpVersion,
        jnlpWorkDir: args.jnlpWorkDir ?: opts.jnlpWorkDir,
    ]
}

def _postDeploy(Map opts) {
    println 'Use Closures to handle Post Deploy Tasks'
    println 'i.e. Front End may need to handle Cache Bust/Seed'
    println 'i.e. Back End may need to handle Database updates'
}
