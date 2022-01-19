
import org.egov.jenkins.Utils
import groovy.io.*

def call(Map params) {
podTemplate(yaml: """
  kind: Pod
  metadata:
    name: build-utils
  spec:
    containers:
    - name: build-utils
      image: egovio/build-utils:7-master-95e76687
      imagePullPolicy: IfNotPresent
      command:
      - cat
      tty: true
      env:
        - name: DOCKER_UNAME
          valueFrom:
            secretKeyRef:
              name: jenkins-credentials
              key: dockerUserName
        - name: DOCKER_UPASS
          valueFrom:
            secretKeyRef:
              name: jenkins-credentials
              key: dockerPassword
        - name: DOCKER_NAMESPACE
          value: egovio
        - name: DOCKER_GROUP_NAME
          value: dev
      resources:
        requests:
          memory: "256Mi"
          cpu: "200m"
        limits:
          memory: "256Mi"
          cpu: "200m"
""")

  {
    node(POD_LABEL) {
      String url = params.url
      String releaseChartDir = "./deploy-as-code/helm/product-release-charts"
      String envdir = "./deploy-as-code/helm/environments"
      List lTargetEnvs = []
      List lProducts = []
      def tmp_file = ".files_list"
      def mapProductsVersionsModules = []

      StringBuilder jobDslScript = new StringBuilder()
      StringBuilder html_to_be_rendered = new StringBuilder()

      String sReleaseChartsDirPath = Utils.getDirName(url)
      dir(sReleaseChartsDirPath) {
          git url: url, credentialsId: "git_read"

          //Read the env files(i) list

          sh "ls ${envdir} > ${tmp_file}"
          lAllEnvs = readFile(tmp_file).split( "\\r?\\n" )
          sh "rm -f ${tmp_file}"
          for (int i = 0; i < lAllEnvs.size(); i++) {
              if (!lAllEnvs[i].contains("secrets") && !lAllEnvs[i].contains("ci")) {
                lTargetEnvs.add(lAllEnvs[i].substring(0, lAllEnvs[i].indexOf(".yaml")))
              }
          }

          //Read the Charts dir list
          
          sh "ls ${releaseChartDir} > ${tmp_file}"
          lProducts = readFile(tmp_file).split( "\\r?\\n" )
          sh "rm -f ${tmp_file}"
          
          //Read all the Product(i) Folder and Go inside to read the chart(j), version(k) and modules (e)
          for (int i = 0; i < lProducts.size(); i++) {

              def lChartFiles = []
              sh "ls ${releaseChartDir}/${lProducts[i]} > ${tmp_file}"
              lChartFiles = readFile(tmp_file).split( "\\r?\\n" )
              sh "rm -f ${tmp_file}"
              lChartFiles.sort()

              Map<String, List<String>> mapCoreModules = new HashMap<>()
              Map<String, List<String>> mapFeatureModules = new HashMap<>()
              Map<String, mapCoreModules, mapFeatureModules> mapVersionsModules = new HashMap<>()

              // Extract the modules from the chart
              for (int k = 0; k < lChartFiles.size(); k++) {
                  def lTotalModules = []
                  Map<String, List<String>> featureModules = new HashMap<>()
                  Map<String, List<String>> coreModules = new HashMap<>()

                  sh "grep 'name:' ${releaseChartDir}/${lProducts[i]}/${lChartFiles[k]} | cut -d' ' -f7 > ${tmp_file}"
                  lTotalModules = readFile(tmp_file).split( "\\r?\\n" )
                  sh "rm -f ${tmp_file}"

                  //Extract the core and feature modules from each speific versions
                  for (int e = 0; e < lTotalModules.size(); e++ ) {

                      if (lTotalModules[e].contains("m_")) {
                          featureModules.add("FeatureModules", lTotalModules[e])
                      } else {
                          coreModules.add("CoreModules", lTotalModules[e])
                      }
                  }
                mapVersionsModules.add(lChartFiles[k].substring(lChartFiles[k].indexOf("-") + 1, lChartFiles[k].indexOf(".y")), coreModules, featureModules)
              }          
              mapProductsVersionsModules.add(lProducts[i], mapVersionsModules)
          }
      }


      jobDslScript.append("""
            pipelineJob("self-provision/deploy") {
                description()
                keepDependencies(false)
                parameters {

                  activeChoiceParam('Environments') {
                        description('Choose the Intended environment to deploy')
                        filterable(false)
                        choiceType('SINGLE_SELECT')
                        groovyScript {
                            script(''' ${lTargetEnvs} ''')
                            fallbackScript('"fallback choice"')
                        }
                    }

                    activeChoiceParam('Project') {
                        description('choose Project from multiple choices')
                        filterable(false)
                        choiceType('SINGLE_SELECT')
                        groovyScript {
                            script(''' ${lProducts} ''')
                            fallbackScript('"fallback choice"')
                        }
                    }

                    activeChoiceReactiveParam('Release-Version') {
                        description('choose release chart version from multiple choices')
                        filterable(false)
                        choiceType('SINGLE_SELECT')
                        groovyScript {
                            script(''' return ${mapVersionsModules.inspect()}.get(Project) ''')
                            fallbackScript('"fallback choice"')
                        }
                        referencedParameter('Project')
                    }

                    activeChoiceReactiveParam('Services') {
                        description('Select the intended modules to be Installed')
                        filterable(false)
                        choiceType('CHECKBOX')
                        groovyScript {
                            script(''' return ${mapProductsVersionsModules.inspect()}.get(Release-Version).get(Project) ''')   
                            fallbackScript('"fallback choice"')
                        }
                        referencedParameter('Release-Version')
                    }
                    booleanParam("Cluster_Configs", false, "Whenever you made changes to the deployment conifg ensure the cluster_config check is checked to pick the latest configs from the deployment")
                    booleanParam("Print_Manifest", true, "Whenever you want to deployment manifest ensure the uncheck checked box")
                }
                definition {
                  cps {
                      script('''library 'ci-libs'
                        selfDeployer(repo:'git@github.com:egovernments/DIGIT-DevOps.git', branch: 'master', helmDir: 'deploy-as-code/helm')''')
                      sandbox()
                  }
                }
                disabled(false)
            }
      """);

      stage('Building jobs') {
        sh """
              echo \"Job DsL Script:  ${jobDslScript.toString()}\"
          """
        jobDsl scriptText: jobDslScript.toString()
      }
    }
  }
}