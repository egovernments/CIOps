import org.egov.jenkins.ConfigParser
import org.egov.jenkins.Utils
import org.egov.jenkins.models.JobConfig
import org.egov.jenkins.models.BuildConfig
import groovy.io.*
import groovy.transform.Field

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
"""
    ) {
        node(POD_LABEL) {
        
        String url = params.url;
        String folderdir = './deploy-as-code/helm/product-release-charts';
        String envdir = './deploy-as-code/helm/environments';
        def dirs = [];
        def envFiles = []
        //def envs = [];
        def tmp_file = ".files_list"
        Map<String,List<String>> jobmap = new HashMap<>();
        //def newdirs = "[\"digit\",\"mgramseva\",\"ifix\"]";
        //def newenvFiles = "[\"dev\",\"stg\",\"uat\"]";
        //@Field Map<String,String> newjobmap = new HashMap<>();
        //newjobmap.put("digit","[\"1.1\",\"1.2\",\"1.3\"]")
        //newjobmap.put("mgramseva","[\"2.1\",\"2.2\",\"2.3\"]")
        //newjobmap.put("ifix","[\"3.1\",\"3.2\",\"3.3\"]")
        StringBuilder jobDslScript = new StringBuilder();
        String directories = "[";
        String subDirectories = "[";
        String envs = "["    
       
            String dirName = Utils.getDirName(url);
            dir(dirName) {
                 git url: url, credentialsId: 'git_read'
                 //def folder = new File("${env.WORKSPACE}/${folderdir}")
                 //sh """
                  //pwd
                  //ls -ltr
                //"""
                 //folder.eachFile FileType.DIRECTORIES, {
                   // dirs << it.name
                 // }
                // dirs = Utils.listFiles(folderdir)
                 
                  sh "ls ${folderdir} > ${tmp_file}"
                  dirs = readFile(tmp_file).split( "\\r?\\n" );
                  sh "rm -f ${tmp_file}"

                dirs.each{ println it }

                for (int i = 0; i < dirs.size(); i++) {
                    directories = directories + "\"" + dirs[i] + "\"";
                    if(i!=dirs.size()-1){
                          directories = directories + ",";
                    }
                }
                directories = directories + "]";
                //directories = directories +","+ "\"" + "core" + "\"" + "]";

                //println directories

            for (int i = 0; i < dirs.size(); i++) {
                   def subfolderlist = []
                   def subFiles = []
                  sh "ls ${folderdir}/${dirs[i]} > ${tmp_file}"
                  subfolderlist = readFile(tmp_file).split( "\\r?\\n" );
                  sh "rm -f ${tmp_file}"

                  for (int j = 0; j < subfolderlist.size(); j++) {
                   subFiles.add(subfolderlist[j].substring(subfolderlist[j].lastIndexOf("-")+1,subfolderlist[j].indexOf(".y")))
                }
              subFiles.each{ println it }
              for (int k = 0; k < subFiles.size(); k++) {
                    subDirectories = subDirectories + "\"" + dirs[i] + "-" + subFiles[i] + "\"";
                    if(i!=subFiles.size()-1){
                          subDirectories = subDirectories + ",";
                    }
                }
                subDirectories = subDirectories + "]";
                jobmap.put(dirs[i], subFiles)
                subDirectories = "[";
            }

            def envfolderlist = []
            sh "ls ${envdir} > ${tmp_file}"
            envfolderlist = readFile(tmp_file).split( "\\r?\\n" );
            sh "rm -f ${tmp_file}"
            for (int i = 0; i < envfolderlist.size(); i++) {
              if (!envfolderlist[i].contains("secrets")) {
                   envFiles.add(envfolderlist[i].substring(0,envfolderlist[i].indexOf(".yaml")))
                }
            }
            for (int i = 0; i < envFiles.size(); i++) {
			  if(envFiles[i] == "ci"){
				 continue;
			}
			else{
			 envs = envs + "\"" + envFiles[i] + "\"";
                    if(i!=envFiles.size()-1){
                          envs = envs + ",";
                    }	
			}
           }
            envs = envs + "]";
        }
            //for (int i = 0; i < envFiles.size(); i++) {
             //       envs = envs + "\"" + envFiles[i] + "\"";   
               //     if(i!=envFiles.size()-1){
                 //         envs = envs + ",";
                  //  }
               // }   
            //envs = envs + "]";  
             
       // }
        //def pos = envs.indexOf("ci")
        //envs.remove(pos);
        //println envs  
            //println envs-("ci")
            //println envs
       // def environments = envs-("ci") 
        //println environments
                //environments.remove("ci");
                
               
        
        Set<String> repoSet = new HashSet<>();
        String repoList = "";

          jobDslScript.append("""
              folder("self-provision")
              """); 

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
                            script(''' ${directories} ''')
                            fallbackScript('"fallback choice"')
                        }
                    }
                    activeChoiceParam('Environments') {
                        description('choose environment from multiple choices')
                        filterable(false)
                        choiceType('SINGLE_SELECT')
                        groovyScript {
                            script(''' ${envs} ''')
                            fallbackScript('"fallback choice"')
                        }
                    }
                    activeChoiceReactiveParam('Release-Version') {
                        description('choose release chart version from multiple choices')
                        filterable(false)
                        choiceType('SINGLE_SELECT')
                        groovyScript {
                            script(''' 
                            def testmap = ${jobmap.inspect()}
                                return testmap.get(Project)''')
                            fallbackScript('"fallback choice"')
                        }
                        referencedParameter('Project')	
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
