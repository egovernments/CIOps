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
        String branch = 'singleinstance';		
        String sChartsDirPath = './deploy-as-code/helm/product-release-charts';
        String sEnvDirPath = './deploy-as-code/helm/environments';
        def tmp_file = ".files_list"
        Map<String,List<String>> mapVersions = new HashMap<>();
        Map<String,List<String>> mapFeatureModules = new HashMap<>();
        Map<String,List<String>> mapCoreModules = new HashMap<>();
        StringBuilder jobDslScript = new StringBuilder();
        String sProducts = "[";
        List <String> lTargetEnvs = [];  
        String sTargetEnvs = "["; 

        String dirName = Utils.getDirName(url);
        dir(dirName) {
            git url: url, branch: branch ,credentialsId: 'git_read'

            // Read Envs
            sh "ls ${sEnvDirPath} > ${tmp_file}"
            lAllEnvs = readFile(tmp_file).split( "\\r?\\n" )
            sh "rm -f ${tmp_file}"
            for (int i = 0; i < lAllEnvs.size(); i++) {
                if (!lAllEnvs[i].contains("-secrets")) {
                  lTargetEnvs.add(lAllEnvs[i].substring(0, lAllEnvs[i].indexOf(".yaml")))
                }
            }
		for (int i = 0; i < lTargetEnvs.size(); i++ ) {
			if(lTargetEnvs[i] == "ci"){
				continue;
			}
			else {	
                          sTargetEnvs = sTargetEnvs + "\"" + lTargetEnvs[i] + "\"";
		          	if(i!=lTargetEnvs.size()-1){
				  sTargetEnvs = sTargetEnvs + ",";
			  }
			}
			}
		  sTargetEnvs = sTargetEnvs + "]";

            // Read products
            sh "ls ${sChartsDirPath} > ${tmp_file}"
            lProducts = readFile(tmp_file).split( "\\r?\\n" );
            sh "rm -f ${tmp_file}"

            for (int i = 0; i < lProducts.size(); i++) {
                sProducts += "\"" + lProducts[i] + "\"";
                if(i !=lProducts.size()-1 ){
                      sProducts +=  ",";
                }
            }
            sProducts += "]";

            for (int i = 0; i < lProducts.size(); i++) {
                def subfolderlist = []
                def lVersions = []
                sh "ls ${sChartsDirPath}/${lProducts[i]} > ${tmp_file}"
                subfolderlist = readFile(tmp_file).split( "\\r?\\n" );
                sh "rm -f ${tmp_file}"

                for (int j = 0; j < subfolderlist.size(); j++) {
                    lVersions.add(subfolderlist[j].substring(subfolderlist[j].indexOf("-")+1,subfolderlist[j].indexOf(".y")))
                }
                lVersions = lVersions.sort()
                mapVersions.put(lProducts[i], lVersions)
                for (int k = 0; k < lVersions.size(); k++){
                    def lFeatureModules = []
                    def lCoreModules = []
                    sh "grep 'name:' ${sChartsDirPath}/${lProducts[i]}/dependancy_chart-${lVersions[k]}.yaml | cut -d' ' -f7 > ${tmp_file}"
                    modulesname = readFile(tmp_file).split( "\\r?\\n" );
                    sh "rm -f ${tmp_file}"

			              for (int e = 0; e < modulesname.size(); e++ ){
                        if(modulesname[e].contains("m_")){
                            lFeatureModules.add(modulesname[e])
                        } else {
                            lCoreModules.add(modulesname[e])
                        }
                        mapFeatureModules.put(lVersions[k], lFeatureModules)
                        mapCoreModules.put(lVersions[k], lCoreModules)  
                    }
                  }   
              }
            }      
    

        jobDslScript.append("""
          pipelineJob("self-provision/deploy") {
            description()
            keepDependencies(false)
            parameters {
                activeChoiceParam('Project') {
                    description('choose Project from multiple choices')
                    filterable(false)
                    choiceType('SINGLE_SELECT')
                    groovyScript {
                        script(''' ${sProducts} ''')
                        fallbackScript('"fallback choice"')
                    }
                }
                activeChoiceReactiveParam('Release_Version') {
                    description('choose release chart version from multiple choices')
                    filterable(false)
                    choiceType('SINGLE_SELECT')
                    groovyScript {
                        script(''' 
                              def mapVersions = ${mapVersions.inspect()}
                              return mapVersions.get(Project)
                        ''')
                        fallbackScript('"fallback choice"')
                    }
                    referencedParameter('Project')	
                }
		activeChoiceParam('Environments') {
                    description('choose environment from multiple choices')
                    filterable(false)
                    choiceType('SINGLE_SELECT')
                    groovyScript {
                        script(''' ${sTargetEnvs} ''')
                        fallbackScript('"fallback choice"')
                    }
                }
                activeChoiceReactiveParam('Core_Platform') {
                    description('choose Modules from release chart from multiple choices')
                    filterable(false)
                    choiceType('CHECKBOX')
                    groovyScript {
                        script(''' 
                              def mapCoreModules = ${mapCoreModules.inspect()}
                              return mapCoreModules.get('digit-v2.6')
                        ''')
                        fallbackScript('"fallback choice"')
                    }
                    referencedParameter('Release_Version')	
                } 

                activeChoiceReactiveParam('Feature_Modules') {
                    description('choose Modules from release chart from multiple choices')
                    filterable(false)
                    choiceType('CHECKBOX')
                    groovyScript {
                        script(''' 
                              def mapFeatureModules = ${mapFeatureModules.inspect()}
                              return mapFeatureModules.get('digit-v2.6')
                        ''')
                        fallbackScript('"fallback choice"')
                    }
                    referencedParameter('Release_Version')	
                }
                booleanParam("Cluster_Configs", false, "Whenever you made changes to the deployment conifg ensure the cluster_config check is checked to pick the latest configs from the deployment")
                booleanParam("Print_Manifest", true, "Whenever you want to deployment manifest ensure the uncheck checked box")
            }  

            definition {
              cps {
                  script('''library 'ci-libs'
                              selfDeployer(repo:'git@github.com:egovernments/DIGIT-DevOps.git', branch: 'singleinstance', helmDir: 'deploy-as-code/helm')''')
                  sandbox() 
                }
            }
            disabled(false)
          }""");
        
          stage('Building jobs') {
            sh """
              echo \"Job DsL Script:  ${jobDslScript.toString()}\"
              """
              jobDsl scriptText: jobDslScript.toString()
              
          }              
      }
      }
}
