import org.egov.jenkins.ConfigParser
import org.egov.jenkins.Utils
import org.egov.jenkins.models.JobConfig
import org.egov.jenkins.models.BuildConfig
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
        memory: "768Mi"
        cpu: "250m"
      limits:
        memory: "1024Mi"
        cpu: "500m"                
"""
    ) {
        node(POD_LABEL) {
        
        String url = "git@github.com:egovernments/DIGIT-DevOps.git";
        String folderdir = './deploy-as-code/helm/release_charts';
        String envdir = './deploy-as-code/helm/environments';
        def dirs = [];
        def envs = [];
        Map<String,List<String>> jobmap = new HashMap<>();
        StringBuilder jobDslScript = new StringBuilder();
       
            String dirName = Utils.getDirName(url);
            dir(dirName) {
                 git url: url, credentialsId: 'git_read'
                 //def folder = new File("${env.WORKSPACE}/${folderdir}")
                 sh """
                  pwd
                  ls -ltr
                """
                 //folder.eachFile FileType.DIRECTORIES, {
                   // dirs << it.name
                 // }
                // dirs = Utils.listFiles(folderdir)
                 def tmp_file = ".files_list"
                  sh """
                  ls ${folderdir} > ${tmp_file}
                  """
                  dirs = readFile(tmp_file).split( "\\r?\\n" );
                  sh """
                  rm -f ${tmp_file}
                  """
             dirs.each{ println it }

            for (int i = 0; i < dirs.size(); i++) {
              def subfolder = new File("${env.WORKSPACE}/${folderdir}/${dirs[i]}")
              def subfolderlist = []
              subfolder.eachFile (FileType.FILES) { subfile ->
                  subfolderlist << subfile.name.substring(subfile.name.lastIndexOf("-")+1,subfile.name.indexOf(".y"))
                }
              jobmap.put(dirs[i], subfolderlist)
            }

            def envfolder = new File("${env.WORKSPACE}/${envdir}")
            def envfolderlist = []
              envfolder.eachFile (FileType.FILES) {
                 if (!it.name.contains("secrets")) {
                      envfolderlist << it.name.substring(0,it.name.indexOf(".yaml"))      
                        }
                }
        }
        
        Set<String> repoSet = new HashSet<>();
        String repoList = "";

          jobDslScript.append("""
              folder("deployer")
              """); 

            jobDslScript.append("""
            pipelineJob("deployer") {
                description()
                keepDependencies(false)
                parameters {
                    activeChoiceParam('Project') {
                        description('choose Project from multiple choices')
                        filterable()
                        choiceType('SINGLE_SELECT')
                        groovyScript {
                            script("${dirs}")
                            fallbackScript('"fallback choice"')
                        }
                    }
                    activeChoiceParam('Environments') {
                        description('choose environment from multiple choices')
                        filterable()
                        choiceType('SINGLE_SELECT')
                        groovyScript {
                            script("${envfolderlist}")
                            fallbackScript('"fallback choice"')
                        }
                    }
                    activeChoiceParam('Release-Chart-Version') {
                        description('choose release chart version from multiple choices')
                        filterable()
                        choiceType('SINGLE_SELECT')
                        groovyScript {
                            script("${jobmap.get(Project)}")
                            fallbackScript('"fallback choice"')
                        }
                    }
                    booleanParam("Cluster_Configs", false, "Whenever you made changes to the deployment conifg ensure the cluster_config check is checked to pick the latest configs from the deployment")
                    booleanParam("Print_Manifest", true, "Whenever you want to deployment manifest ensure the uncheck checked box")
                }  
                definition {
                  cps {
                      script(library 'ci-libs'
                        infradeployer(repo:'git@github.com:egovernments/DIGIT-DevOps.git', branch: 'master', helmDir: 'deploy-as-code/helm'))
                      sandbox() 
                    }
                }
                disabled(false)
              }            
""");
        
        

        stage('Building jobs') {
           //jobDsl scriptText: jobDslScript.toString()
            sh """
           echo \"Job DsL Script:  ${jobDslScript.toString()}\"
            """
        }              

    }

}
}
