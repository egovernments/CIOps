import org.egov.jenkins.ConfigParser
import org.egov.jenkins.Utils
import org.egov.jenkins.models.JobConfig
import org.egov.jenkins.models.BuildConfig

def call(Map params) {

    podTemplate(yaml: """
kind: Pod
metadata:
  name: build-utils
spec:
  nodeSelector:
    dedicated: egov-jenkins
  tolerations:
    - effect: NoSchedule
      key: dedicated
      operator: Equal
      value: egov-jenkins
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
        try {

        // Two calling conventions are supported:
        //   jobBuilder(urls:  ['git@host:org/repo.git', ...])                 <- legacy
        //   jobBuilder(repos: [[url:'git@host:org/repo.git', branch:'main']])  <- per-repo branch
        // 'urls' is normalised into the 'repos' shape with an empty branch, so
        // existing callers keep working untouched.
        List repoSpecs = params.repos ?: (params.urls ?: []).collect { [url: it, branch: ''] };
        String configFile = './build/build-config.yml';
        Map<String,List<JobConfig>> jobConfigMap=new HashMap<>();
        Map<String,String> branchByUrl = new HashMap<>();
        StringBuilder jobDslScript = new StringBuilder();
        List<String> allJobConfigs = new ArrayList<>();

        for (int i = 0; i < repoSpecs.size(); i++) {
            String gitUrl = repoSpecs[i].url;
            String wanted = repoSpecs[i].branch ?: '';
            String dirName = Utils.getDirName(gitUrl);
            String usedBranch = wanted;
            dir(dirName) {
                 if (wanted) {
                     git url: gitUrl, branch: wanted, credentialsId: 'git_read'
                 } else {
                     // No branch configured. Attempt 'master' first -- that is what
                     // `git url:` defaulted to before this change, so every repo that
                     // built successfully before still resolves identically here.
                     // Only if 'master' is absent do we consult the remote's default
                     // branch (covers repos that live on 'main', 'develop', etc).
                     try {
                         git url: gitUrl, branch: 'master', credentialsId: 'git_read'
                         usedBranch = 'master'
                     } catch (Exception e) {
                         String resolved = ''
                         // ssh-agent plugin is not installed; sshUserPrivateKey from
                         // ssh-credentials + credentials-binding gives us the key file.
                         withCredentials([sshUserPrivateKey(credentialsId: 'git_read', keyFileVariable: 'GIT_KEY')]) {
                             resolved = sh(returnStdout: true, script: 'GIT_SSH_COMMAND="ssh -i $GIT_KEY -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null" git ls-remote --symref ' + gitUrl + ' HEAD 2>/dev/null | awk \'/^ref:/ {sub("refs/heads/","",$2); print $2; exit}\'').trim()
                         }
                         if (!resolved) {
                             error("jobBuilder: no 'master' branch on ${gitUrl} and the remote default branch could not be resolved. Set an explicit branch for this repo in jobBuilder.repos.")
                         }
                         echo "jobBuilder: ${gitUrl} has no 'master'; using remote default branch '${resolved}'"
                         git url: gitUrl, branch: resolved, credentialsId: 'git_read'
                         usedBranch = resolved
                     }
                 }
                 def yaml = readYaml file: configFile;
                 List<JobConfig> jobConfigs = ConfigParser.populateConfigs(yaml.config, env);
                 jobConfigMap.put(gitUrl,jobConfigs);
                 branchByUrl.put(gitUrl, usedBranch);
                 allJobConfigs.addAll(jobConfigs);
            }
        }
        
        Set<String> repoSet = new HashSet<>();
        String repoList = "";

        List<String> folders = Utils.foldersToBeCreatedOrUpdated(allJobConfigs, env);
                  for (int j = 0; j < folders.size(); j++) {
                      jobDslScript.append("""
                          folder("${folders[j]}")
                          """);
                    }

        for (Map.Entry<Integer, String> entry : jobConfigMap.entrySet()) {   

            List<JobConfig> jobConfigs = entry.getValue();
            // Default the generated job's BRANCH param to the branch this repo was
            // actually read from, instead of a hardcoded 'origin/master' (which is
            // wrong for repos whose default branch is 'main'). Falls back to
            // 'master' so behaviour is unchanged for repos resolved that way.
            String jobBranch = branchByUrl.get(entry.getKey()) ?: 'master';

        for (int i = 0; i < jobConfigs.size(); i++) {

            for(int j=0; j<jobConfigs.get(i).getBuildConfigs().size(); j++){
                BuildConfig buildConfig = jobConfigs.get(i).getBuildConfigs().get(j);
                repoSet.add(buildConfig.getImageName());                    
            }  

            repoList = String.join(",", repoSet);     

            jobDslScript.append("""
            pipelineJob("${jobConfigs.get(i).getName()}") {
                logRotator(-1, 5, -1, -1)
                parameters {  
                  gitParameterDefinition {
                        name('BRANCH')
                        type('PT_BRANCH_TAG')
                        description('') 
                        branch('')      
                        useRepository('')                     
                        defaultValue('origin/${jobBranch}')
                        branchFilter('.*')
                        tagFilter('*')
                        sortMode('ASCENDING_SMART')
                        selectedValue('DEFAULT')
                        quickFilterEnabled(true)
                        listSize('5')                 
                }
                  booleanParam('ALT_REPO_PUSH', false, 'Check to push images to GCR')
                  stringParam('REACT_APP_PUBLIC_PATH', '', 'Specify the public path for the React app')
            }
                definition {
                    cpsScm {
                        scm {
                            git{
                                remote {
                                    url("${entry.getKey()}")
                                    credentials('git_read')
                                } 
                                branch ('\${BRANCH}')
                                scriptPath('Jenkinsfile')
                                extensions { }
                            }
                        }

                    }
                }
            }
""");
        }
        }

        stage('Building jobs') {
           jobDsl scriptText: jobDslScript.toString()
        }

        stage('Creating Repositories in DockerHub') {
                    withEnv(["REPO_LIST=${repoList}"
                    ]) {
                        container(name: 'build-utils', shell: '/bin/sh') {
                            sh (script:'sh /tmp/scripts/create_repo.sh')
                           //sh (script:'echo \$REPO_LIST')
                        }
                    }
        }

        } finally {
            cleanWs()
        }

    }

}
}
