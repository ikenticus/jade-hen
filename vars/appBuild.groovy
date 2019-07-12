#!/usr/bin/env groovy

def call(Map args) {
    properties([
        disableConcurrentBuilds()
    ])

    def opts = args.buildOpts ? args.buildOpts.call(args) : _buildOpts(args)
    currentBuild.description = "${opts.appName} ${opts.version}"
    notifySlack('Initiating Build')

    podTemplate(label: 'jenkins-slave-docker', containers: [
        containerTemplate(name: 'jnlp', image: "${opts.jnlpImage}:${opts.jnlpVersion}",
                            args: '${computer.jnlpmac} ${computer.name}', workingDir: "${opts.jnlpWorkDir}",
                            resourceRequestCpu: "${opts.podReqCpu}", resourceLimitCpu: "${opts.podResCpu}",
                            resourceRequestMemory: "${opts.podReqMem}", resourceLimitMemory: "${opts.podResMem}"),
        containerTemplate(name: 'docker', image: "${opts.dockerImage}:${opts.dockerVersion}", command: 'cat', ttyEnabled: true)
    ],
    volumes:[
        hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
    ]) {
        node ('jenkins-slave-docker') {
            withCredentials([string(credentialsId: 'aws-id', variable: 'AWS_ID')]) {

                def dockerRepo = "https://${AWS_ID}.dkr.ecr.${opts.region}.amazonaws.com"

                stage('Checkout') {
                    try {
                        deleteDir()
                        checkout([
                            $class: 'GitSCM',
                            branches: scm.branches,
                            userRemoteConfigs: scm.userRemoteConfigs,
                            extensions: [[$class: 'CloneOption', noTags: false, shallow: false, depth: 0, reference: '']]
                        ])
                        opts.version = env.BRANCH_NAME.replace('_', '-').toLowerCase()
                        currentBuild.description = "${opts.appName} ${opts.version}"
                    } catch (e) {
                        notifySlack("ERROR git checkout: ${e}")
                        throw e
                    }
                }

                stage('Tagging') {
                    try {
                        def topTag = sh (script: 'git describe --abbrev=0 --tags', returnStdout: true).trim()
                        def topText = sh (script: 'git show --name-status', returnStdout: true).trim()
                        def oldVersion = new SemVer(topTag)
                        opts.nextVersion = oldVersion.bump(topText).toString()

                        if (env.BRANCH_NAME == opts.master) {
                            opts.flag = 'live'
                            opts.namespace = 'staging'
                            opts.version = opts.nextVersion
                        }
                        currentBuild.description = "${opts.appName} ${opts.version}"
                    } catch (e) {
                        notifySlack("ERROR tagging app: ${e}")
                        throw e
                    }
                }

                stage('Build') {
                    container('docker') {
                        try {
                            docker.withRegistry("${dockerRepo}/${opts.appName}/${opts.flag}", "ecr:${opts.region}:jenkins-iam") {
                                println 'Execute Unit Tests within Dockerfile since every coding language varies'
                                args.dockerBuildArgs ? args.dockerBuildArgs.call(opts) : _dockerBuildArgs(opts)

                                def buildArgs = ''
                                if (opts.buildArgs.size() > 0) {
                                    buildArgs = '--build-arg ' + opts.buildArgs.collect { key, value -> "${key}=${value}" }.join(' --build-arg ')
                                }
                                docker.build("${opts.appName}/${opts.flag}:${opts.version}", "${buildArgs} -f ci/Dockerfile .").push()
                            }
                            sh "docker images"
                            //sh "docker rmi ${opts.appName}/${opts.flag}:${opts.version} ${dockerRepo}/${opts.appName}/${opts.flag}:${opts.version}"
                            //sh "docker images"
                        } catch (e) {
                            notifySlack("ERROR with docker: ${e}")
                            throw e
                        }
                    }

                    if (opts.pushGitTags && env.BRANCH_NAME == opts.master) {
                        try {
                            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'jenkins-https',
                                usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD']]
                            ) {
                                sh "git config user.email jenkins@${opts.domain}"
                                sh 'git config user.name jenkins'
                                sh "git config credential.username ${env.GIT_USERNAME}"
                                sh "git config credential.helper '!f() { echo password=\$GIT_PASSWORD; }; f'"
                                sh "git tag -a ${opts.version} -m ${opts.version}"
                                sh "GIT_ASKPASS=true git push origin --tags"
                            }
                        } catch (e) {
                            notifySlack("ERROR git tag: ${e}")
                            throw e
                        }
                    }
                }

                stage('Deploy') {
                    try {
                        println " Deploying ${opts.appName} ${opts.version} to ${opts.namespace}"
                        build job: "deploy-${opts.appName}", wait: false, parameters: [
                            string(name: 'namespace', value: opts.namespace),
                            string(name: 'version', value: opts.version)
                        ]
                        notifySlack('SUCCESS building app')
                    } catch (e) {
                        notifySlack("ERROR building app: ${e}")
                        throw e
                    }
                }
            }
        }
    }
}

Map _buildOpts(Map args) {
    def opts = common.podOpts()

    return [
        appName: args.appName ?: 'unnamed',
        buildArgs: args.buildArgs ?: [:],
        domain: args.domain ?: opts.domain,
        flag: args.flag ?: 'test',
        master: args.master ?: opts.master,
        namespace: args.namespace ?: 'test',
        region: args.region ?: opts.region,
        version: args.version ?: 'latest',

        dockerImage: args.dockerImage ?: opts.dockerImage,
        dockerVersion: args.dockerVersion ?: opts.dockerVersion,

        jnlpImage: args.jnlpImage ?: opts.jnlpImage,
        jnlpVersion: args.jnlpVersion ?: opts.jnlpVersion,
        jnlpWorkDir: args.jnlpWorkDir ?: opts.jnlpWorkDir,

        podReqCpu: args.podReqCpu ?: opts.podReqCpu,
        podReqMem: args.podReqMem ?: opts.podReqMem,
        podResCpu: args.podResCpu ?: opts.podResCpu,
        podResMem: args.podResMem ?: opts.podResMem,

        pushGitTags: args.pushGitTags ?: opts.pushGitTags,
    ]
}

def _dockerBuildArgs(Map opts) {
    opts.buildArgs.APP_VER = opts.nextVersion
}

class SemVer implements Serializable {

    private int major, minor, patch

    SemVer(String version) {
        def versionParts = version.tokenize('.')
        println versionParts
        if (versionParts.size() != 3) {
            throw new IllegalArgumentException("Wrong version format - expected MAJOR.MINOR.PATCH - got ${version}")
        }
        this.major = versionParts[0].replaceAll("[^\\d-]", "").toInteger()
        this.minor = versionParts[1].replaceAll("[^\\d-]", "").toInteger()
        this.patch = versionParts[2].replaceAll("[^\\d-]", "").toInteger()
    }

    SemVer(int major, int minor, int patch) {
        this.major = major
        this.minor = minor
        this.patch = patch
    }

    SemVer bump(String status) {
        if (status.indexOf('MAJOR') > -1) {
            return new SemVer(major + 1, 0, 0)
        } else if (status.indexOf('MINOR') > - 1) {
            return new SemVer(major, minor + 1, 0)
        } else {
            return new SemVer(major, minor, patch + 1)
        }
    }

    String toString() {
        return "v${major}.${minor}.${patch}"
    }

}
