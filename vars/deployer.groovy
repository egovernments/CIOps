library 'ci-libs'

def call(Map pipelineParams) {

podTemplate(yaml: """
kind: Pod
metadata:
  name: egov-deployer
spec:
  securityContext:
    fsGroup: 1000
  nodeSelector:
    dedicated: egov-jenkins
  tolerations:
    - effect: NoSchedule
      key: dedicated
      operator: Equal
      value: egov-jenkins
  containers:
  - name: egov-deployer
    image: egovio/egov-deployer:azure-deploy
    command:
    - cat
    tty: true
    securityContext:
      runAsUser: 1000
      runAsNonRoot: true
    env:
      - name: "GOOGLE_APPLICATION_CREDENTIALS"
        value: "/var/run/secret/cloud.google.com/service-account.json"
      - name: "KUBECONFIG"
        value: "/tmp/kube-config/config"
    volumeMounts:
      - name: service-account
        mountPath: /var/run/secret/cloud.google.com
      - name: kube-config
        mountPath: /tmp/kube-config
    resources:
      requests:
        memory: "256Mi"
        cpu: "200m"
      limits:
        memory: "256Mi"
        cpu: "200m"
  serviceAccount: jenkins
  serviceAccountName: jenkins
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
        secretName: "${pipelineParams.environment}-kube-config"                    
"""
    ) {
        node(POD_LABEL) {
            try {
                git url: pipelineParams.repo, branch: pipelineParams.branch, credentialsId: 'git_read'
                stage('Deploy Images') {
                    container(name: 'egov-deployer', shell: '/bin/sh') {
                        sh """
                            /opt/egov/egov-deployer deploy --helm-dir `pwd`/${pipelineParams.helmDir} -c=${env.CLUSTER_CONFIGS} -e ${pipelineParams.environment} "${env.IMAGES}"
                        """
                    }
                }
            } finally {
                cleanWs()
            }
        }
    }


}
