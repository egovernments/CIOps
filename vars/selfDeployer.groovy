library 'ci-libs'

def call(Map pipelineParams) {

podTemplate(yaml: """
kind: Pod
metadata:
  name: egov-infra-deployer
spec:
  containers:
  - name: egov-infra-deployer
    image: gajendranegov/new-deployer:1
    command:
    - cat
    tty: true
    env:  
      - name: "GOOGLE_APPLICATION_CREDENTIALS"
        value: "/var/run/secret/cloud.google.com/service-account.json"              
    volumeMounts:
      - name: service-account
        mountPath: /var/run/secret/cloud.google.com
      - name: kube-config
        mountPath: /root/.kube     
    resources:
      requests:
        memory: "320Mi"
        cpu: "300m"
      limits:
        memory: "320Mi"
        cpu: "300m"  
  volumes:
  - name: service-account
    projected:
      sources:
      - secret:
          name: jenkins-credentials
          items:
            - key: gcpKmsServiceAccount
              path: service-account.json   
  - name: kube-config
    secret:
        secretName: "${env.ENVIRONMENTS}-kube-config"                    
"""
    ) {
        node(POD_LABEL) {
            git url: pipelineParams.repo, branch: pipelineParams.branch, credentialsId: 'git_read'
                stage('Deploy Images') {
                        container(name: 'egov-infra-deployer', shell: '/bin/sh') {
                            sh """
                                /opt/egov/egov-deployer deploy --helm-dir `pwd`/${pipelineParams.helmDir} -c=${env.CLUSTER_CONFIGS} -e ${env.ENVIRONMENTS} -p=${env.PRINT_MANIFEST} -s=${env.PROJECT} -v=${env.RELEASE_VERSION}
                            """
                            }
                }
        }
    }


}
