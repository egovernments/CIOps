
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
      def lTargetEnvs = []
      def lProducts = []
      def lCharts = []
      def lVersions = []
      def lFeatureModules = []
      def lCoreModules = []
      def tmp_file = ".files_list"
      Map<String,List<String>> mapProductsVersions = new HashMap<>()
      Map<String,List<String>> mapVersionsFeatureModules = new HashMap<>()
      Map<String,List<String>> mapVersionsCoreModules = new HashMap<>()
      StringBuilder jobDslScript = new StringBuilder()
      String sProducts = "["
      String sEnvs = "["

      String sReleaseChartsDirPath = Utils.getDirName(url)
      dir(sReleaseChartsDirPath) {
          git url: url, credentialsId: "git_read"

          //Read the env files(i) list
          sh "ls ${envdir} > ${tmp_file}"
          lEnvs = readFile(tmp_file).split( "\\r?\\n" )
          sh "rm -f ${tmp_file}"
          for (int i = 0; i < lEnvs.size(); i++) {
              if (!lEnvs[i].contains("secrets") && lEnvs[i].contains("ci")) {
                lTargetEnvs.add(lEnvs[i].substring(0, lEnvs[i].indexOf(".yaml")))
              }
          }

          //Read the Charts dir list
          sh "ls ${releaseChartDir} > ${tmp_file}"
          lProducts = readFile(tmp_file).split( "\\r?\\n" )
          sh "rm -f ${tmp_file}"
          for (int i = 0; i < lTargetEnvs.size(); i++) {
              sEnvs = sEnvs + "\"" + lTargetEnvs[i] + "\""
              if (i != lTargetEnvs.size() - 1) {
                sEnvs =+ ","
              }
          }
          sEnvs =+ "]"

          //Read all the Product(i) Folder and Go inside to read the chart(j), version(k) and modules (e)
          for (int i = 0; i < lProducts.size(); i++) {
              sProducts =+ "\"" + lProducts[i] + "\""
              if (i != lProducts.size() - 1) {
                  sProducts =+ ","
              }

              sh "ls ${releaseChartDir}/${lProducts[i]} > ${tmp_file}"
              lCharts = readFile(tmp_file).split( "\\r?\\n" )
              sh "rm -f ${tmp_file}"


              // Read the chart files
              for (int j = 0; j < lCharts.size(); j++) {
                  lVersions.add(lCharts[j].substring(lCharts[j].indexOf("-") + 1, lCharts[j].indexOf(".y")))
              }
              lVersions.each { println it }
              lVersions = lVersions.sort()
              mapProductsVersions.put(lProducts[i], lVersions)

              // Extract the versions from the chart
              for (int k = 0; k < lVersions.size(); k++) {
                  sh "grep 'name:' ${releaseChartDir}/${lProducts[i]}/dependancy_chart-${lVersions[k]}.yaml | cut -d' ' -f7 > ${tmp_file}"
                  lTotalModules = readFile(tmp_file).split( "\\r?\\n" )
                  sh "rm -f ${tmp_file}"

                  //Extract the core and feature modules from each speific versions
                  for (int e = 0; e < lTotalModules.size(); e++ ) {
                      if (lTotalModules[e].contains("m_")) {
                          lFeatureModules.add(lTotalModules[e])
                      } else {
                          lCoreModules.add(lTotalModules[e])
                      }
                      mapVersionsFeatureModules.put(lVersions[k], lFeatureModules)
                      mapVersionsCoreModules.put(lVersions[k], lCoreModules)
                  }
              }

          }
          sProducts =+ "]"
      }

      //jobDslScript.append("""folder("self-provision")"""); 

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
                            script(""" ${sEnvs} """)
                            fallbackScript('"fallback choice"')
                        }
                    }

                    activeChoiceParam('Project') {
                        description('choose Project from multiple choices')
                        filterable(false)
                        choiceType('SINGLE_SELECT')
                        groovyScript {
                            script(""" ${sProducts} """)
                            fallbackScript('"fallback choice"')
                        }
                    }

                    activeChoiceReactiveParam('Release-Version') {
                        description('choose release chart version from multiple choices')
                        filterable(false)
                        choiceType('SINGLE_SELECT')
                        groovyScript {
                            script(""" return ${mapProductsVersions.inspect()}.get(Project) """)
                            fallbackScript('"fallback choice"')
                        }
                        referencedParameter('Project')
                    }

                    activeChoiceReactiveParam('Core-Modules') {
                        description('Select the intended modules to be Installed')
                        filterable(false)
                        choiceType('CHECKBOX')
                        groovyScript {
                            script(""" return ${mapVersionsCoreModules.inspect()}.get(Release-Version) """)
                            fallbackScript('"fallback choice"')
                        }
                        referencedParameter('Release-Version')
                    }

                    activeChoiceReactiveParam('Feature-Modules') {
                        description('Select the intended modules to be Installed')
                        filterable(false)
                        choiceType('CHECKBOX')
                        groovyScript {
                            script(""" return ${mapVersionsFeatureModules.inspect()}.get(Release-Version) """)
                            fallbackScript('"fallback choice"')
                        }
                        referencedParameter('Release-Version')
                    }
                    booleanParam("Cluster_Configs", false, "Whenever you made changes to the deployment conifg ensure the cluster_config check is checked to pick the latest configs from the deployment")
                    booleanParam("Print_Manifest", true, "Whenever you want to deployment manifest ensure the uncheck checked box")
                }
                definition {
                  cps {
                      script("""library 'ci-libs'
                        selfDeployer(repo:'git@github.com:egovernments/DIGIT-DevOps.git', branch: 'master', helmDir: 'deploy-as-code/helm')""")
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
