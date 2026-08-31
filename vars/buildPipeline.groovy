import org.egov.jenkins.ConfigParser
import org.egov.jenkins.models.BuildConfig
import org.egov.jenkins.models.JobConfig

import static org.egov.jenkins.ConfigParser.getCommonBasePath

library 'ci-libs'

def call(Map pipelineParams) {

    def kanikoResources = pipelineParams.resources ?: [
        app: [requests: [cpu: '900m',  memory: '2200Mi'], limits: [cpu: '1500m', memory: '5120Mi']],
        db:  [requests: [cpu: '300m',  memory: '512Mi'],  limits: [cpu: '600m',  memory: '1024Mi']]
    ]

    // Lightweight orchestrator pod — only used for git parsing and manifest creation
    podTemplate(yaml: """
kind: Pod
metadata:
  name: ci-orchestrator
spec:
  securityContext:
    fsGroup: 0
  nodeSelector:
    dedicated: egov-jenkins
  tolerations:
    - effect: NoSchedule
      key: dedicated
      operator: Equal
      value: egov-jenkins
  containers:
  - name: git
    image: docker.io/egovio/builder:2-64da60a1-version_script_update-NA
    imagePullPolicy: IfNotPresent
    command:
    - cat
    tty: true
  - name: manifest
    image: gcr.io/go-containerregistry/crane:debug
    imagePullPolicy: IfNotPresent
    command:
    - /busybox/sh
    tty: true
    env:
      - name: DOCKER_CONFIG
        value: /root/.docker
    volumeMounts:
      - name: jenkins-docker-cfg
        mountPath: /root/.docker
  volumes:
  - name: jenkins-docker-cfg
    projected:
      sources:
      - secret:
          name: jenkins-credentials
          items:
            - key: dockerConfigJson
              path: config.json
"""
    ) {
        node(POD_LABEL) {
          try {

            def scmVars = shallowCheckout()
            String REPO_NAME = env.REPO_NAME ? env.REPO_NAME : "docker.io/egovio"
            String GCR_REPO_NAME = "asia.gcr.io/digit-egov"
            def yaml = readYaml file: pipelineParams.configFile
            List<JobConfig> jobConfigs = ConfigParser.parseConfig(yaml, env)

            for (int i = 0; i < jobConfigs.size(); i++) {
                JobConfig jobConfig = jobConfigs.get(i)

                stage('Parse Latest Git Commit') {
                    withEnv(["BUILD_PATH=${jobConfig.getBuildConfigs().get(0).getWorkDir()}",
                             "PATH=alpine:$PATH"
                    ]) {
                        container(name: 'git', shell: '/bin/sh') {
                            scmVars['VERSION'] = sh(script: '/scripts/get_application_version.sh ${BUILD_PATH}', returnStdout: true).trim()
                            scmVars['ACTUAL_COMMIT'] = sh(script: '/scripts/get_folder_commit.sh ${BUILD_PATH}', returnStdout: true).trim()
                            scmVars['BRANCH'] = scmVars['GIT_BRANCH'].replaceFirst("origin/", "")
                        }
                    }
                }

                // Capture into finals for safe closure use in parallel branches
                final String baseTag = scmVars.BRANCH.equalsIgnoreCase("master")
                    ? "v${scmVars.VERSION}-${scmVars.ACTUAL_COMMIT}-${env.BUILD_NUMBER}"
                    : "${scmVars.BRANCH}-${scmVars.ACTUAL_COMMIT}-${env.BUILD_NUMBER}"
                final String finalRepoName = REPO_NAME
                final String finalGcrRepoName = GCR_REPO_NAME
                final String noPushImage = env.NO_PUSH ? env.NO_PUSH : "false"
                final String reactAppPublicPathArg = env.REACT_APP_PUBLIC_PATH ? "--build-arg REACT_APP_PUBLIC_PATH=${env.REACT_APP_PUBLIC_PATH}" : ""
                final List<BuildConfig> buildConfigs = jobConfig.getBuildConfigs()
                final String altRepoPush = env.ALT_REPO_PUSH ?: "false"

                stage('Build multi-arch') {
                    // amd64 and arm64 run in parallel; within each arch, buildConfigs run as
                    // sequential stage() calls so Pipeline Graph View renders them as branch nodes.
                    parallel(
                        failFast: false,
                        'amd64': {
                            for (int j = 0; j < buildConfigs.size(); j++) {
                                final BuildConfig bc = buildConfigs.get(j)
                                final String workDir = bc.getWorkDir().replaceFirst(getCommonBasePath(bc.getWorkDir(), bc.getDockerFile()), "./")
                                final String amd64Image = "${finalRepoName}/${bc.getImageName()}:${baseTag}-amd64"
                                final String bcLabel = bc.getImageName().endsWith('-db') ? 'db' : 'app'
                                final def res = kanikoResources[bcLabel] ?: kanikoResources.app
                                stage(bcLabel) {
                                    podTemplate(yaml: """
kind: Pod
metadata:
  name: kaniko-amd64
spec:
  securityContext:
    fsGroup: 0
  nodeSelector:
    dedicated: egov-jenkins
  tolerations:
    - effect: NoSchedule
      key: dedicated
      operator: Equal
      value: egov-jenkins
  containers:
  - name: kaniko
    image: gcr.io/kaniko-project/executor:v1.23.2-debug
    imagePullPolicy: IfNotPresent
    securityContext:
      runAsUser: 0
      privileged: true
    command:
    - /busybox/cat
    tty: true
    env:
      - name: GIT_ACCESS_TOKEN
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: gitReadAccessToken
      - name: "GOOGLE_APPLICATION_CREDENTIALS"
        value: "/var/run/secret/cloud.google.com/service-account.json"
      - name: NEXUS_USERNAME
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: nexusUsername
      - name: NEXUS_PASSWORD
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: nexusPassword
      - name: CI_DB_USER
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: ciDbUsername
      - name: CI_DB_PWD
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: ciDbpassword
      - name: REGISTRY_MIRROR_HOST
        valueFrom:
          fieldRef:
            fieldPath: status.hostIP
    volumeMounts:
      - name: jenkins-docker-cfg
        mountPath: /kaniko/.docker
      - name: service-account
        mountPath: /var/run/secret/cloud.google.com
    resources:
      requests:
        cpu: "${res.requests.cpu}"
        memory: "${res.requests.memory}"
      limits:
        cpu: "${res.limits.cpu}"
        memory: "${res.limits.memory}"
  volumes:
  - name: service-account
    projected:
      sources:
      - secret:
          name: jenkins-credentials
          items:
            - key: gcpServiceAccount
              path: service-account.json
  - name: jenkins-docker-cfg
    projected:
      sources:
      - secret:
          name: jenkins-credentials
          items:
            - key: dockerConfigJson
              path: config.json
""") {
                                        node(POD_LABEL) {
                                            shallowCheckout()
                                            echo "${bc.getWorkDir()} ${bc.getDockerFile()}"
                                            if (!fileExists(bc.getWorkDir()) || !fileExists(bc.getDockerFile()))
                                                throw new Exception("Working directory / dockerfile does not exist!")
                                            container(name: 'kaniko', shell: '/busybox/sh') {
                                                withEnv(["PATH=/busybox:/kaniko:$PATH"]) {
                                                    if (altRepoPush.equalsIgnoreCase("true")) {
                                                        final String gcrImage = "${finalGcrRepoName}/${bc.getImageName()}:${env.BUILD_NUMBER}-${scmVars.BRANCH}-${scmVars.VERSION}-${scmVars.ACTUAL_COMMIT}-amd64"
                                                        sh """
                                                            /kaniko/executor \\
                                                              -f `pwd`/${bc.getDockerFile()} \\
                                                              -c `pwd`/${bc.getContext()} \\
                                                              --build-arg WORK_DIR=${workDir} \\
                                                              --build-arg token=\$GIT_ACCESS_TOKEN \\
                                                              ${reactAppPublicPathArg} \\
                                                              --build-arg nexusUsername=\$NEXUS_USERNAME \\
                                                              --build-arg nexusPassword=\$NEXUS_PASSWORD \\
                                                              --build-arg ciDbUsername=\$CI_DB_USER \\
                                                              --build-arg ciDbpassword=\$CI_DB_PWD \\
                                                              --registry-mirror=\${REGISTRY_MIRROR_HOST}:5000 \\
                                                              --custom-platform=linux/amd64 \\
                                                              --snapshot-mode=redo \\
                                                              --cache=true --cache-repo=349271159511.dkr.ecr.ap-south-1.amazonaws.com/kaniko-cache-amd64 \\
                                                              --destination=${amd64Image} \\
                                                              --destination=${gcrImage} \\
                                                              --no-push=${noPushImage}
                                                        """
                                                        echo "${amd64Image} and ${gcrImage} pushed!"
                                                    } else {
                                                        sh """
                                                            /kaniko/executor \\
                                                              -f `pwd`/${bc.getDockerFile()} \\
                                                              -c `pwd`/${bc.getContext()} \\
                                                              --build-arg WORK_DIR=${workDir} \\
                                                              --build-arg token=\$GIT_ACCESS_TOKEN \\
                                                              ${reactAppPublicPathArg} \\
                                                              --build-arg nexusUsername=\$NEXUS_USERNAME \\
                                                              --build-arg nexusPassword=\$NEXUS_PASSWORD \\
                                                              --build-arg ciDbUsername=\$CI_DB_USER \\
                                                              --build-arg ciDbpassword=\$CI_DB_PWD \\
                                                              --registry-mirror=\${REGISTRY_MIRROR_HOST}:5000 \\
                                                              --custom-platform=linux/amd64 \\
                                                              --snapshot-mode=redo \\
                                                              --cache=true --cache-repo=349271159511.dkr.ecr.ap-south-1.amazonaws.com/kaniko-cache-amd64 \\
                                                              --destination=${amd64Image} \\
                                                              --no-push=${noPushImage}
                                                        """
                                                        echo "${amd64Image} pushed!"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        'arm64': {
                            for (int j = 0; j < buildConfigs.size(); j++) {
                                final BuildConfig bc = buildConfigs.get(j)
                                final String workDir = bc.getWorkDir().replaceFirst(getCommonBasePath(bc.getWorkDir(), bc.getDockerFile()), "./")
                                final String arm64Image = "${finalRepoName}/${bc.getImageName()}:${baseTag}-arm64"
                                final String bcLabel = bc.getImageName().endsWith('-db') ? 'db' : 'app'
                                final def res = kanikoResources[bcLabel] ?: kanikoResources.app
                                stage(bcLabel) {
                                    podTemplate(yaml: """
kind: Pod
metadata:
  name: kaniko-arm64
spec:
  securityContext:
    fsGroup: 0
  nodeSelector:
    dedicated: egov-jenkins
  tolerations:
    - effect: NoSchedule
      key: dedicated
      operator: Equal
      value: egov-jenkins
  containers:
  - name: kaniko
    image: gcr.io/kaniko-project/executor:v1.23.2-debug
    imagePullPolicy: IfNotPresent
    securityContext:
      runAsUser: 0
      privileged: true
    command:
    - /busybox/cat
    tty: true
    env:
      - name: GIT_ACCESS_TOKEN
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: gitReadAccessToken
      - name: "GOOGLE_APPLICATION_CREDENTIALS"
        value: "/var/run/secret/cloud.google.com/service-account.json"
      - name: NEXUS_USERNAME
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: nexusUsername
      - name: NEXUS_PASSWORD
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: nexusPassword
      - name: CI_DB_USER
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: ciDbUsername
      - name: CI_DB_PWD
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: ciDbpassword
      - name: REGISTRY_MIRROR_HOST
        valueFrom:
          fieldRef:
            fieldPath: status.hostIP
    volumeMounts:
      - name: jenkins-docker-cfg
        mountPath: /kaniko/.docker
      - name: service-account
        mountPath: /var/run/secret/cloud.google.com
    resources:
      requests:
        cpu: "${res.requests.cpu}"
        memory: "${res.requests.memory}"
      limits:
        cpu: "${res.limits.cpu}"
        memory: "${res.limits.memory}"
  volumes:
  - name: service-account
    projected:
      sources:
      - secret:
          name: jenkins-credentials
          items:
            - key: gcpServiceAccount
              path: service-account.json
  - name: jenkins-docker-cfg
    projected:
      sources:
      - secret:
          name: jenkins-credentials
          items:
            - key: dockerConfigJson
              path: config.json
""") {
                                        node(POD_LABEL) {
                                            shallowCheckout()
                                            if (!fileExists(bc.getWorkDir()) || !fileExists(bc.getDockerFile()))
                                                throw new Exception("Working directory / dockerfile does not exist!")
                                            container(name: 'kaniko', shell: '/busybox/sh') {
                                                withEnv(["PATH=/busybox:/kaniko:$PATH"]) {
                                                    if (altRepoPush.equalsIgnoreCase("true")) {
                                                        final String gcrImage = "${finalGcrRepoName}/${bc.getImageName()}:${env.BUILD_NUMBER}-${scmVars.BRANCH}-${scmVars.VERSION}-${scmVars.ACTUAL_COMMIT}-arm64"
                                                        sh """
                                                            /kaniko/executor \\
                                                              -f `pwd`/${bc.getDockerFile()} \\
                                                              -c `pwd`/${bc.getContext()} \\
                                                              --build-arg WORK_DIR=${workDir} \\
                                                              --build-arg token=\$GIT_ACCESS_TOKEN \\
                                                              ${reactAppPublicPathArg} \\
                                                              --build-arg nexusUsername=\$NEXUS_USERNAME \\
                                                              --build-arg nexusPassword=\$NEXUS_PASSWORD \\
                                                              --build-arg ciDbUsername=\$CI_DB_USER \\
                                                              --build-arg ciDbpassword=\$CI_DB_PWD \\
                                                              --registry-mirror=\${REGISTRY_MIRROR_HOST}:5000 \\
                                                              --custom-platform=linux/arm64 \\
                                                              --snapshot-mode=redo \\
                                                              --cache=true --cache-repo=349271159511.dkr.ecr.ap-south-1.amazonaws.com/kaniko-cache-arm64 \\
                                                              --destination=${arm64Image} \\
                                                              --destination=${gcrImage} \\
                                                              --no-push=${noPushImage}
                                                        """
                                                        echo "${arm64Image} and ${gcrImage} pushed!"
                                                    } else {
                                                        sh """
                                                            /kaniko/executor \\
                                                              -f `pwd`/${bc.getDockerFile()} \\
                                                              -c `pwd`/${bc.getContext()} \\
                                                              --build-arg WORK_DIR=${workDir} \\
                                                              --build-arg token=\$GIT_ACCESS_TOKEN \\
                                                              ${reactAppPublicPathArg} \\
                                                              --build-arg nexusUsername=\$NEXUS_USERNAME \\
                                                              --build-arg nexusPassword=\$NEXUS_PASSWORD \\
                                                              --build-arg ciDbUsername=\$CI_DB_USER \\
                                                              --build-arg ciDbpassword=\$CI_DB_PWD \\
                                                              --registry-mirror=\${REGISTRY_MIRROR_HOST}:5000 \\
                                                              --custom-platform=linux/arm64 \\
                                                              --snapshot-mode=redo \\
                                                              --cache=true --cache-repo=349271159511.dkr.ecr.ap-south-1.amazonaws.com/kaniko-cache-arm64 \\
                                                              --destination=${arm64Image} \\
                                                              --no-push=${noPushImage}
                                                        """
                                                        echo "${arm64Image} pushed!"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    )
                }

                stage('Create multi-arch manifests') {
                    List<String> amd64Lines = []
                    List<String> arm64Lines = []
                    List<String> finalLines = []

                    container(name: 'manifest', shell: '/busybox/sh') {
                        for (BuildConfig bc : buildConfigs) {
                            String amd64Img = "${REPO_NAME}/${bc.getImageName()}:${baseTag}-amd64"
                            String arm64Img = "${REPO_NAME}/${bc.getImageName()}:${baseTag}-arm64"
                            String multiArchImage = "${REPO_NAME}/${bc.getImageName()}:${baseTag}"
                            sh """
                                /ko-app/crane index append \\
                                  --tag ${multiArchImage} \\
                                  -m ${amd64Img} \\
                                  -m ${arm64Img}
                            """
                            amd64Lines << "${amd64Img}"
                            arm64Lines << "${arm64Img}"
                            finalLines << "${multiArchImage}"
                        }
                    }

                    echo """
------------------------------------------------------------
Published images:
AMD-64:
${amd64Lines.collect { "  ${it}" }.join('\n')}

ARM-64:
${arm64Lines.collect { "  ${it}" }.join('\n')}

FINAL (multi-arch):
${finalLines.collect { "  ${it}" }.join('\n')}
------------------------------------------------------------"""

                    writeFile file: 'final-output.html', text: """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>Final Output</title>
<style>
  body  { margin:0; padding:20px; background:#1e1e1e; color:#d4d4d4; font-family:'Courier New',monospace; font-size:13px; line-height:1.6; }
  .sep  { color:#555; }
  .hdr  { color:#4ec9b0; font-weight:bold; font-size:15px; margin-top:14px; }
  .img  { color:#dcdcaa; padding-left:20px; }
</style>
</head>
<body>
<div class="sep">------------------------------------------------------------</div>
<div class="hdr"><b>AMD-64:</b></div>
${amd64Lines.collect { "<div class='img'>${it}</div>" }.join('\n')}
<br>
<div class="hdr"><b>ARM-64:</b></div>
${arm64Lines.collect { "<div class='img'>${it}</div>" }.join('\n')}
<br>
<div class="hdr"><b>FINAL (multi-arch):</b></div>
${finalLines.collect { "<div class='img'>${it}</div>" }.join('\n')}
<div class="sep">------------------------------------------------------------</div>
</body>
</html>"""
                    archiveArtifacts artifacts: 'final-output.html', allowEmptyArchive: false
                }
            }
          } catch (Exception e) {
              throw e
          } finally {
              cleanWs()
          }
        }
    }

}

private def shallowCheckout() {
    checkout([
        $class: 'GitSCM',
        branches: scm.branches,
        extensions: [
            [$class: 'CloneOption', depth: 1, shallow: true, noTags: true],
        ],
        userRemoteConfigs: scm.userRemoteConfigs
    ])
}
