import org.egov.jenkins.ConfigParser
import org.egov.jenkins.models.BuildConfig
import org.egov.jenkins.models.JobConfig

import static org.egov.jenkins.ConfigParser.getCommonBasePath

library 'ci-libs'

def call(Map pipelineParams) {

    // Lightweight orchestrator pod — only used for git parsing and manifest creation
    podTemplate(yaml: """
kind: Pod
metadata:
  name: ci-orchestrator
spec:
  securityContext:
    fsGroup: 0
  containers:
  - name: git
    image: docker.io/egovio/builder:2-64da60a1-version_script_update-NA
    imagePullPolicy: IfNotPresent
    command:
    - cat
    tty: true
  - name: manifest
    image: docker:26-cli
    imagePullPolicy: IfNotPresent
    command:
    - cat
    tty: true
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

            def scmVars = checkout scm
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
                    // Both pods are created simultaneously — neither waits for the other
                    parallel(
                        failFast: false,
                        'amd64': {
                            podTemplate(yaml: """
kind: Pod
metadata:
  name: kaniko-amd64
spec:
  securityContext:
    fsGroup: 0
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
      - name: token
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
    volumeMounts:
      - name: jenkins-docker-cfg
        mountPath: /root/.docker
      - name: kaniko-cache
        mountPath: /cache
      - name: service-account
        mountPath: /var/run/secret/cloud.google.com
    resources:
      requests:
        memory: "2200Mi"
        cpu: "900m"
      limits:
        memory: "5120Mi"
        cpu: "1500m"
  volumes:
  - name: kaniko-cache
    persistentVolumeClaim:
      claimName: kaniko-cache-claim
      readOnly: true
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
                                    checkout scm
                                    container(name: 'kaniko', shell: '/busybox/sh') {
                                        withEnv(["PATH=/busybox:/kaniko:$PATH"]) {
                                            for (BuildConfig bc : buildConfigs) {
                                                echo "${bc.getWorkDir()} ${bc.getDockerFile()}"
                                                if (!fileExists(bc.getWorkDir()) || !fileExists(bc.getDockerFile()))
                                                    throw new Exception("Working directory / dockerfile does not exist!")

                                                String workDir = bc.getWorkDir().replaceFirst(getCommonBasePath(bc.getWorkDir(), bc.getDockerFile()), "./")
                                                String amd64Image = "${finalRepoName}/${bc.getImageName()}:${baseTag}-amd64"
                                                echo "Building ${amd64Image}"

                                                if (altRepoPush.equalsIgnoreCase("true")) {
                                                    String gcrImage = "${finalGcrRepoName}/${bc.getImageName()}:${env.BUILD_NUMBER}-${scmVars.BRANCH}-${scmVars.VERSION}-${scmVars.ACTUAL_COMMIT}-amd64"
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
                                                          --cache=true --cache-dir=/cache --cache-repo=egovio/cache \\
                                                          --single-snapshot=true --snapshotMode=time \\
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
                                                          --cache=true --cache-dir=/cache --cache-repo=egovio/cache \\
                                                          --single-snapshot=true --snapshotMode=time \\
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
                        },
                        'arm64': {
                            podTemplate(yaml: """
kind: Pod
metadata:
  name: kaniko-arm64
spec:
  securityContext:
    fsGroup: 0
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
    volumeMounts:
      - name: jenkins-docker-cfg
        mountPath: /root/.docker
    resources:
      requests:
        memory: "2200Mi"
        cpu: "900m"
      limits:
        memory: "5120Mi"
        cpu: "1500m"
  volumes:
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
                                    checkout scm
                                    container(name: 'kaniko', shell: '/busybox/sh') {
                                        withEnv(["PATH=/busybox:/kaniko:$PATH"]) {
                                            for (BuildConfig bc : buildConfigs) {
                                                String workDir = bc.getWorkDir().replaceFirst(getCommonBasePath(bc.getWorkDir(), bc.getDockerFile()), "./")
                                                String arm64Image = "${finalRepoName}/${bc.getImageName()}:${baseTag}-arm64"
                                                echo "Building ${arm64Image}"
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
                                                      --customPlatform=linux/arm64 \\
                                                      --cache=true --cache-repo=egovio/cache-arm64 \\
                                                      --single-snapshot=true --snapshotMode=time \\
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
                    )
                }

                stage('Create multi-arch manifests') {
                    container(name: 'manifest', shell: '/bin/sh') {
                        for (BuildConfig bc : buildConfigs) {
                            String multiArchImage = "${REPO_NAME}/${bc.getImageName()}:${baseTag}"
                            sh """
                                docker buildx imagetools create \\
                                  --tag ${multiArchImage} \\
                                  ${REPO_NAME}/${bc.getImageName()}:${baseTag}-amd64 \\
                                  ${REPO_NAME}/${bc.getImageName()}:${baseTag}-arm64
                            """
                            echo "${multiArchImage} (multi-arch manifest) created!"
                        }
                    }
                }
            }
        }
    }

}
