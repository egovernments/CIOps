library 'ci-libs'

def call(Map pipelineParams) {

podTemplate(yaml: """
kind: Pod
metadata:
  name: egov-deployer
spec:
  containers:
  - name: egov-deployer
    image: egovio/egov-deployer:helmfile-integration
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
        memory: "256Mi"
        cpu: "200m"
      limits:
        memory: "1024Mi"
        cpu: "500m"  
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
            git url: pipelineParams.repo, branch: pipelineParams.branch, credentialsId: 'git_read'
            stage('Deploy Images') {
                container(name: 'egov-deployer', shell: '/bin/bash') {
                    sh """
                        set +x
                        server_url=\$(kubectl config view -o jsonpath='{.clusters[0].cluster.server}')
                        server_ip=\$(echo "\$server_url" | sed 's|https://||' | cut -d: -f1)

                        if [ -f "\$GOOGLE_APPLICATION_CREDENTIALS" ]; then
                            gcloud auth activate-service-account --key-file="\$GOOGLE_APPLICATION_CREDENTIALS"
                            gcloud config list
                            echo "GCLOUD successfully set-up"
                        else
                          echo "GOOGLE_APPLICATION_CREDENTIALS not set or file missing"
                        fi

                        echo ""
                        if [ "${env.LEGACY_DEPLOYER}" = "true" ]; then
                          echo "Legacy deploy mode enabled. Running legacy (go) deploy command"
                          CMD="/opt/egov/egov-deployer deploy --helm-dir \$(pwd)/${pipelineParams.helmDir} -c=${env.CLUSTER_CONFIGS} -e ${pipelineParams.environment} \"${env.IMAGES}\""
                          echo "Executing: \$CMD"
                          eval "\$CMD"
                          exit \$?
                        fi

                        if [ -z "${env.IMAGES}" ]; then
                          echo "No services selected to deploy. Exiting."
                          exit 1
                        fi

                        CMD="helmfile -f ${pipelineParams.helmDir}/digit-helmfile.yaml -e ${pipelineParams.environment}"

                        if [ "${env.CLUSTER_CONFIGS}" = "true" ]; then
                          CMD="\$CMD --selector target=./configmaps"
                        fi

                        echo ""
                        if [ "${env.IMAGES}" = "ALL" ]; then
                          CMD="\$CMD apply"
                          echo "Deploying all services via: \$CMD"
                          eval "\$CMD"
                          exit \$?
                        fi

                        echo ""
                        echo "Deploying below services:"
                        SERVICE_ARGS=""

                        IMAGES_LIST="${env.IMAGES}"
                        OLD_IFS="\$IFS"
                        IFS=','

                        for entry in \$IMAGES_LIST; do
                          if [ "\$entry" = "ALL" ]; then
                            continue
                          fi

                          if echo "\$entry" | grep -q ':'; then
                            svc=\$(echo "\$entry" | cut -d: -f1)
                            tag=\$(echo "\$entry" | cut -d: -f2)
                            echo "service: \$svc --> image: \$svc:\$tag"
                            SERVICE_ARGS="\$SERVICE_ARGS --selector target=./\$svc --set \$svc.image.tag=\$tag"
                          else
                            echo "service: \$entry"
                            SERVICE_ARGS="\$SERVICE_ARGS --selector target=./\$entry"
                          fi
                        done

                        IFS="\$OLD_IFS"

                        echo ""
                        CMD="\$CMD \$SERVICE_ARGS apply"
                        echo "Executing: \$CMD"
                        eval "\$CMD"
                """
                }
            }
        }
    }
}
