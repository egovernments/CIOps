
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
      List lVersions = []
      List lFeatureModules = []
      List lCoreModules = []
      def tmp_file = ".files_list"
      Map<String, List<String>> mapProductsVersions = new HashMap<>()
      Map<String, List<String>> mapVersionsFeatureModules = new HashMap<>()
      Map<String, List<String>> mapVersionsCoreModules = new HashMap<>()
      def mapProductsVersionsModules = new HashMap<>()

      StringBuilder jobDslScript = new StringBuilder()

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
          lProdFolders = readFile(tmp_file).split( "\\r?\\n" )
          sh "rm -f ${tmp_file}"
          
          //Read all the Product(i) Folder and Go inside to read the chart(j), version(k) and modules (e)
          for (int i = 0; i < lProdFolders.size(); i++) {
              lProducts.add(lProdFolders[i])

              sh "ls ${releaseChartDir}/${lProdFolders[i]} > ${tmp_file}"
              lChartFiles = readFile(tmp_file).split( "\\r?\\n" )
              sh "rm -f ${tmp_file}"
              lChartFiles.sort()

              // Read the chart files and Extract the versions from the chart
              for (int j = 0; j < lChartFiles.size(); j++) {
                  lVersions.add(lChartFiles[j].substring(lChartFiles[j].indexOf("-") + 1, lChartFiles[j].indexOf(".y")))
              }
              lVersions.sort()

              // Extract the modules from the chart
              for (int k = 0; k < lChartFiles.size(); k++) {
                  sh "grep 'name:' ${releaseChartDir}/${lProdFolders[i]}/${lChartFiles[k]} | cut -d' ' -f7 > ${tmp_file}"
                  lTotalModules = readFile(tmp_file).split( "\\r?\\n" )
                  sh "rm -f ${tmp_file}"

                  //Extract the core and feature modules from each speific versions
                  for (int e = 0; e < lTotalModules.size(); e++ ) {
                      if (lTotalModules[e].contains("m_")) {
                          lFeatureModules.add(lTotalModules[e])
                      } else {
                          lCoreModules.add(lTotalModules[e])
                      }

                  }
                  
                  mapVersionsModules["FeatureModules"] = lFeatureModules
                  mapVersionsModules["CoreModules"] = lCoreModules
                  mapVersionsAllModules["${lVersions[k]}"] = mapVersionsModules 
              }
              
            mapProductsVersions["${lProdFolders[i]}"] = lVersions      
            mapProductsVersionsModules["${lProdFolders[i]}"] = mapVersionsAllModules
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
                            script(''' return ${mapProductsVersions.inspect()}.get(Project) ''')
                            fallbackScript('"fallback choice"')
                        }
                        referencedParameter('Project')
                    }

                    activeChoiceReactiveParam('Services') {
                        description('Select the intended modules to be Installed')
                        filterable(false)
                        choiceType('ET_FORMATTED_HTML')
                        groovyScript {
                            script(''' 
                                  html_to_be_rendered = "<table><tr>"
                                  service_list = ${mapVersionsModules.inspect()}.get(Release-Version)
                                  service_list.each { service ->
                                    html_to_be_rendered = """
                                      ${html_to_be_rendered}
                                      <tr>
                                      <td>
                                      <input name=\"value\" alt=\"${service}\" json=\"${service}\" type=\"checkbox\" class=\" \">
                                      <label title=\"${service}\" class=\" \">${service}</label>
                                      </td>
                                      </tr>
                                  """
                                  }
                                  html_to_be_rendered = "${html_to_be_rendered}</tr></table>"

                                  return html_to_be_rendered
                              ''')    
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