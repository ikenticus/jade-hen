#!/usr/bin/env groovy

def call(Map args) {
    properties([
        disableConcurrentBuilds()
    ])

    def opts = args.buildOpts ? args.buildOpts.call(args) : _buildOpts(args)
    currentBuild.description = "${opts.appName} ${opts.version}"
    notifySlack("INIT Build branch: ${BUILD_URL}")

    podTemplate(label: "jenkins-build-${opts.appName}", containers: [
        containerTemplate(name: 'docker', image: "${opts.dockerImage}:${opts.dockerVersion}", command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'kube', image: "${opts.kubeImage}:${opts.kubeVersion}", command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'test', image: "${opts.testImage}:${opts.testVersion}", command: 'cat', ttyEnabled: true),
    ],
    volumes:[
        hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
    ]) {
        node ("jenkins-build-${opts.appName}") {
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

                        if (BUILD_NUMBER.toInteger() > 1 && !opts.skipGitDiff) {
                            // first build NEVER gets to skip Docker stage
                            println "Checking git differences:"
                            opts.skipBuild = true
                            gitDiff = sh(script: "git diff --name-only HEAD~1", returnStdout: true).trim()
                            for (file in gitDiff.split('\n')) {
                                if (!file.startsWith('ci/helm/')) {
                                    opts.skipBuild = false
                                }
                            }
                            println gitDiff
                            println "Skip docker build: ${opts.skipBuild}"
                        }

                        opts.version = env.BRANCH_NAME.replace('_', '-').toLowerCase()
                        currentBuild.description = "${opts.appName} ${opts.version}"
                    } catch (e) {
                        notifySlack("ERROR Checkout git ${opts.version}: ${e}")
                        throw e
                    }
                }

                stage('Unit Tests') {
                    if (!opts.skipBuild) {
                        container('test') {
                            try {
                                // Execute Unit Tests within "test" container since every coding language varies
                                args.testUnit ? args.testUnit.call(opts) : _testUnit(opts)
                            } catch (e) {
                                notifySlack("ERROR Failed Unit Test(s) ${opts.version}: ${e}")
                                throw e
                            }
                        }
                    }
                }

                stage('Tagging') {
                    try {
                        def topTag = ''
                        try {
                            topTag = sh (script: 'git describe --abbrev=0 --tags', returnStdout: true).trim()
                        } catch (e) {
                            topTag = 'v0.0.0'
                        }
                        def topText = sh (script: 'git show --name-status', returnStdout: true).trim()
                        def oldVersion = new SemVer(topTag)

                        // pushGitTags assumes automatic version bump, else leave as-is
                        if (opts.pushGitTags && !opts.skipBuild) {
                            opts.nextVersion = oldVersion.bump(topText).toString()
                        } else {
                            opts.nextVersion = oldVersion.toString()
                        }

                        if (env.BRANCH_NAME == opts.master) {
                            opts.flagRepo = 'live'
                            opts.version = opts.nextVersion
                            opts.namespace = 'staging'
                        }
                        currentBuild.description = "${opts.appName} ${opts.version}"
                    } catch (e) {
                        notifySlack("ERROR Tagging ${opts.version}: ${e}")
                        throw e
                    }
                }

                stage('Build') {
                    if (!opts.skipBuild) {
                        container('docker') {
                            try {
                                docker.withRegistry("${dockerRepo}/${opts.appName}/${opts.flagRepo}", "ecr:${opts.region}:jenkins-iam") {
                                    args.dockerBuildArgs ? args.dockerBuildArgs.call(opts) : _dockerBuildArgs(opts)

                                    def buildArgs = ''
                                    if (opts.buildArgs.size() > 0) {
                                        buildArgs = '--build-arg ' + opts.buildArgs.collect { key, value -> "${key}=${value}" }.join(' --build-arg ')
                                    }
                                    docker.build("${opts.appName}/${opts.flagRepo}:${opts.version}",
                                        "--network host ${buildArgs} -f ci/Dockerfile .").push()
                                }
                                sh "docker images"
                                //sh "docker rmi ${opts.appName}/${opts.flagRepo}:${opts.version} ${dockerRepo}/${opts.appName}/${opts.flagRepo}:${opts.version}"
                                //sh "docker images"
                            } catch (e) {
                                notifySlack("ERROR Docker issues: ${e}")
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
                                notifySlack("ERROR Pushing git tag: ${e}")
                                throw e
                            }
                        }
                    }
                }

                stage('Deploy') {
                    try {
                        def autodeploy = true
                        def reason = 'unknown issues'
                        opts.branch = env.BRANCH_NAME
                        if (env.BRANCH_NAME == opts.master) {
                            opts.branch = opts.version
                        } else if (args.includes) {
                            autodeploy = false
                            reason = 'include patterns'
                            args.includes.each {
                                if (env.BRANCH_NAME.contains(it)) {
                                    autodeploy = true
                                    println "Include ${it}"
                                }
                            }
                        }
                        if (args.excludes && autodeploy) {
                            args.excludes.each { exclude ->
                                if (env.BRANCH_NAME.contains(exclude)) {
                                    autodeploy = false
                                    reason = 'exclude patterns'
                                    println "Exclude ${exclude}"
                                }
                            }
                        }
                        if (autodeploy) {
                            // Pull Requests needs to get actual branch name not PR name
                            if (env.CHANGE_BRANCH) opts.branch = env.CHANGE_BRANCH
                            println " Deploying ${opts.appName} ${opts.version} to ${opts.namespace}"
                            build job: "deploy-${opts.appName}", wait: false, parameters: [
                                string(name: 'master', value: opts.master),
                                string(name: 'version', value: opts.branch),
                                string(name: 'namespace', value: opts.namespace),
                                string(name: 'continuous', value: opts.continuous)
                            ]
                            notifySlack("SUCCESS Building ${opts.version}: ${BUILD_URL}console")
                        } else {
                            notifySlack("""SUCCESS Building ${opts.version}: ${BUILD_URL}console
                                AutoDeploy cancelled due to ${reason}.""")
                        }
                    } catch (e) {
                        notifySlack("ERROR Building ${opts.version}: ${e} (${BUILD_URL}console)")
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
        continuous: args.continuous ?: opts.continuous,
        domain: args.domain ?: opts.domain,
        flagRepo: args.flagRepo ?: 'test',
        master: args.master ?: opts.master,
        namespace: args.namespace ?: 'test',
        region: args.region ?: opts.region,
        version: args.version ?: 'latest',

        dockerImage: args.dockerImage ?: opts.dockerImage,
        dockerVersion: args.dockerVersion ?: opts.dockerVersion,

        kubeImage: args.kubeImage ?: opts.kubeImage,
        kubeVersion: args.kubeVersion ?: opts.kubeVersion,

        pushGitTags: args.pushGitTags ?: opts.pushGitTags,
        skipGitDiff: args.skipGitDiff ?: opts.skipGitDiff,

        testImage: args.testImage ?: opts.testImage,
        testVersion: args.testVersion ?: opts.testVersion,
    ]
}

def _dockerBuildArgs(Map opts) {
    opts.buildArgs.APP_VER = opts.nextVersion
    def route53 = common.checkReleaseName(opts.appName, opts.version)
    opts.buildArgs.SITE_HOST = "https://${route53}.${opts.region}.${opts.domain}"
}

def _testUnit(Map opts) {
    if (fileExists('test/unit')) {
        println 'Running Unit Tests'
    } else {
        println 'No Unit Tests Found'
    }
}

class SemVer implements Serializable {

    private int major, minor, patch

    SemVer(String version) {
        def versionParts = version.tokenize('-')[0].tokenize('.')
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
