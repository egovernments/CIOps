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
        String url = params.url;
        String folderdir = './deploy-as-code/helm/product-release-charts';
        String envdir = './deploy-as-code/helm/environments';
        def dirs = [];
        def envFiles = []
        def tmp_file = ".files_list"
        Map<String,List<String>> mapVersions = new HashMap<>();
        Map<String,List<String>> mapFeatureModules = new HashMap<>();
        Map<String,List<String>> mapCoreModules = new HashMap<>();
        StringBuilder jobDslScript = new StringBuilder();
        String sProducts = "[";
        List<String> lTargetEnvs = []    
        String dirName = Utils.getDirName(url);
        dir(dirName) {
            git url: url, credentialsId: 'git_read'

            // Read products
            sh "ls ${folderdir} > ${tmp_file}"
            dirs = readFile(tmp_file).split( "\\r?\\n" );
            sh "rm -f ${tmp_file}"

            for (int i = 0; i < dirs.size(); i++) {
                sProducts = sProducts + "\"" + dirs[i] + "\"";
                if(i!=dirs.size()-1){
                      sProducts = sProducts + ",";
                }
            }
            sProducts = sProducts + "]";

            // Read Envs
            sh "ls ${envdir} > ${tmp_file}"
            lAllEnvs = readFile(tmp_file).split( "\\r?\\n" )
            sh "rm -f ${tmp_file}"
            for (int i = 0; i < lAllEnvs.size(); i++) {
                if (!lAllEnvs[i].contains("secrets") && !lAllEnvs[i].contains("ci")) {
                  lTargetEnvs.add(lAllEnvs[i].substring(0, lAllEnvs[i].indexOf(".yaml")))
                }
            }

            for (int i = 0; i < dirs.size(); i++) {
                def subfolderlist = []
                def subFiles = []
                sh "ls ${folderdir}/${dirs[i]} > ${tmp_file}"
                subfolderlist = readFile(tmp_file).split( "\\r?\\n" );
                sh "rm -f ${tmp_file}"

                for (int j = 0; j < subfolderlist.size(); j++) {
                    subFiles.add(subfolderlist[j].substring(subfolderlist[j].indexOf("-")+1,subfolderlist[j].indexOf(".y")))
                }
                subFiles = subFiles.sort()
                mapVersions.put(dirs[i], subFiles)
                for (int k = 0; k < subFiles.size(); k++){
                    def lFeatureModules = []
                    def lCoreModules = []
                    sh "grep 'name:' ${folderdir}/${dirs[i]}/dependancy_chart-${subFiles[k]}.yaml | cut -d' ' -f7 > ${tmp_file}"
                    modulesname = readFile(tmp_file).split( "\\r?\\n" );
                    sh "rm -f ${tmp_file}"

			              for (int e = 0; e < modulesname.size(); e++ ){
                      if(modulesname[e].contains("m_")){
                          lFeatureModules.add(modulesname[e])
                      } else {
                          lCoreModules.add(modulesname[e])
                      }
                      mapFeatureModules.put(subFiles[k], lFeatureModules)
                      mapCoreModules.put(subFiles[k], lCoreModules)  
                    }
                }  
            }

        }      
    

        jobDslScript.append("""
          pipelineJob("self-provision/deploy") {
            description()
            keepDependencies(false)
            parameters {
                activeChoiceParam('Environments') {
                    description('choose environment from multiple choices')
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
                        script(''' ${sProducts} ''')
                        fallbackScript('"fallback choice"')
                    }
                }
                activeChoiceReactiveParam('Release-Version') {
                    description('choose release chart version from multiple choices')
                    filterable(false)
                    choiceType('SINGLE_SELECT')
                    groovyScript {
                        script(''' 
                        def testmap = ${mapVersions.inspect()}
                            return testmap.get(Project)''')
                        fallbackScript('"fallback choice"')
                    }
                    referencedParameter('Project')	
                }
                activeChoiceReactiveParam('Core-Platform') {
                    description('choose Modules from release chart from multiple choices')
                    filterable(false)
                    choiceType('CHECKBOX')
                    groovyScript {
                        script(''' 
                        def mapCoreModules = ${mapCoreModules.inspect()}
                        return mapCoreModules.get(Release-Version)''')
                        fallbackScript('"fallback choice"')
                    }
                    referencedParameter('Release-Version')	
                } 

                activeChoiceReactiveParam('Feature-Modules') {
                    description('choose Modules from release chart from multiple choices')
                    filterable(false)
                    choiceType('CHECKBOX')
                    groovyScript {
                        script(''' 
                        def mapFeatureModules = ${mapFeatureModules.inspect()}
                        return mapFeatureModules.get(Release-Version)''')
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