import com.cloudbees.groovy.cps.NonCPS
import com.sap.piper.ConfigurationHelper
import com.sap.piper.GenerateStageDocumentation
import com.sap.piper.JenkinsUtils
import com.sap.piper.LegacyConfigurationCheckUtils
import com.sap.piper.StageNameProvider
import com.sap.piper.Utils
import com.sap.piper.k8s.ContainerMap
import groovy.transform.Field
import sun.font.Script

import static com.sap.piper.Prerequisites.checkScript

@Field String STEP_NAME = getClass().getName()
@Field String TECHNICAL_STAGE_NAME = 'init'

@Field Set GENERAL_CONFIG_KEYS = [
    /**
     * Defines the build tool used.
     * @possibleValues `docker`, `kaniko`, `maven`, `mta, ``npm`
     */
    'buildTool',
    /**
     * Defines the library resource containing the container map.
     */
    'containerMapResource',
    /**
     * Enable automatic inference of build tool (maven, npm, mta) based on existing project files.
     * If this is set to true, it is not required to set the build tool by hand for those cases.
     */
    'inferBuildTool',
    /**
     * Toggle for initialization of the Cloud SDK Pipeline.
     * If this is set to true, the stashSettings parameter is **not** configurable and will be initialized based on the buildTool used'.
     * In addition, if the execution happens on K8S the step artifactPrepareVersion is **not** executed inside a docker container, but on the node instead.
     * This requires a maven executable to be available.
     */
    'initCloudSdk',
    /**
     * Defines the library resource containing the legacy configuration mapping.
     */
    'legacyConfigSettings',
    /**
     * Defines the main branch for your pipeline. **Typically this is the `master` branch, which does not need to be set explicitly.** Only change this in exceptional cases
     */
    'productiveBranch',
    /**
     * Defines the library resource containing stage/step initialization settings. Those define conditions when certain steps/stages will be activated. **Caution: changing the default will break the standard behavior of the pipeline - thus only relevant when including `Init` stage into custom pipelines!**
     */
    'stageConfigResource',
    /**
     * Defines the library resource containing the stash settings to be performed before and after each stage. **Caution: changing the default will break the standard behavior of the pipeline - thus only relevant when including `Init` stage into custom pipelines!**
     */
    'stashSettings',
    /**
     * Whether verbose output should be produced.
     * @possibleValues `true`, `false`
     */
    'verbose'
]
@Field STAGE_STEP_KEYS = []
@Field Set STEP_CONFIG_KEYS = GENERAL_CONFIG_KEYS.plus(STAGE_STEP_KEYS)
@Field Set PARAMETER_KEYS = STEP_CONFIG_KEYS.plus([
    /**
     * Enables the use of technical stage names.
     */
    'useTechnicalStageNames',
])

/**
 * This stage initializes the pipeline run and prepares further execution.
 *
 * It will check out your repository and perform some steps to initialize your pipeline run.
 */
@GenerateStageDocumentation(defaultStageName = 'Init')
void call(Map parameters = [:]) {

    def script = checkScript(this, parameters) ?: this
    def utils = parameters.juStabUtils ?: new Utils()
    def scmInfo

    if (parameters.useTechnicalStageNames) {
        StageNameProvider.instance.useTechnicalStageNames = true
    }

    def stageName = StageNameProvider.instance.getStageName(script, parameters, this)
    println("Thats the stageName: ${stageName}")
/*
    if (Boolean.valueOf(env.ON_K8S) && parameters.containerMapResource && parameters.initCloudSdk) {
        scmInfo = checkout scm

        setupCommonPipelineEnvironment script: script, customDefaults: parameters.customDefaults
        ContainerMap.instance.initFromResource(script, parameters.containerMapResource, script.commonPipelineEnvironment.buildTool)

        stash allowEmpty: true, excludes: '', includes: '**', useDefaultExcludes: false, name: 'INIT'
        script.commonPipelineEnvironment.configuration.stageStashes = [ (stageName): [ unstash : ["INIT"]]]
    }
*/
    piperStageWrapper (script: script, stageName: stageName, stashContent: [], ordinal: 1, telemetryDisabled: true) {
  //      if (!parameters.initCloudSdk) {
            scmInfo = checkout scm

            setupCommonPipelineEnvironment script: script, customDefaults: parameters.customDefaults
    //    }

        Map config = ConfigurationHelper.newInstance(this)
            .loadStepDefaults()
            .mixinGeneralConfig(script.commonPipelineEnvironment, GENERAL_CONFIG_KEYS)
            .mixinStageConfig(script.commonPipelineEnvironment, stageName, STEP_CONFIG_KEYS)
            .mixin(parameters, PARAMETER_KEYS)
            .addIfEmpty('stageConfigResource', 'com.sap.piper/pipeline/stageDefaults.yml')
            .addIfEmpty('stashSettings', 'com.sap.piper/pipeline/stashSettings.yml')
            .addIfEmpty('buildTool', script.commonPipelineEnvironment.buildTool)
            .withMandatoryProperty('buildTool')
            .use()

        if (config.legacyConfigSettings) {
            Map legacyConfigSettings = readYaml(text: libraryResource(config.legacyConfigSettings))
            LegacyConfigurationCheckUtils.checkConfiguration(script, legacyConfigSettings)
        }

        String buildTool = checkBuildTool(script, config)

        //perform stashing based on library resource piper-stash-settings.yml if not configured otherwise or Cloud SDK Pipeline is initialized
        if (config.initCloudSdk) {
            switch (buildTool) {
                case 'maven':
                    initStashConfiguration(script, "com.sap.piper/pipeline/cloudSdkJavaStashSettings.yml", config.verbose?: false)
                    break
                case 'npm':
                    initStashConfiguration(script, "com.sap.piper/pipeline/cloudSdkJavascriptStashSettings.yml", config.verbose?: false)
                    break
                case 'mta':
                    initStashConfiguration(script, "com.sap.piper/pipeline/cloudSdkMtaStashSettings.yml", config.verbose?: false)
                    break
            }
        } else {
            initStashConfiguration(script, config.stashSettings, config.verbose?: false)
        }

        setGitUrlsOnCommonPipelineEnvironment(script, scmInfo.GIT_URL)
        script.commonPipelineEnvironment.setGitCommitId(scmInfo.GIT_COMMIT)

        if (config.verbose) {
            echo "piper-lib-os  configuration: ${script.commonPipelineEnvironment.configuration}"
        }

        // telemetry reporting
        utils.pushToSWA([step: STEP_NAME], config)

        piperInitRunStageConfiguration script: script, stageConfigResource: config.stageConfigResource

        // CHANGE_ID is set only for pull requests
        if (env.CHANGE_ID) {
            List prActions = []

            //get trigger action from comment like /piper action
            def jenkinsUtils = new JenkinsUtils()
            def commentTriggerAction = jenkinsUtils.getIssueCommentTriggerAction()

            if (commentTriggerAction != null) prActions.add(commentTriggerAction)

            try {
                prActions.addAll(pullRequest.getLabels().asList())
            } catch (ex) {
                echo "[${STEP_NAME}] GitHub labels could not be retrieved from Pull Request, please make sure that credentials are maintained on multi-branch job."
            }


            setPullRequestStageStepActivation(script, config, prActions)
        }

        if (env.BRANCH_NAME == config.productiveBranch) {
            if (parameters.script.commonPipelineEnvironment.configuration.runStep?.get('Init')?.slackSendNotification) {
                slackSendNotification script: script, message: "STARTED: Job <${env.BUILD_URL}|${URLDecoder.decode(env.JOB_NAME, java.nio.charset.StandardCharsets.UTF_8.name())} ${env.BUILD_DISPLAY_NAME}>", color: 'WARNING'
            }
            //if (config.inferBuildTool && env.ON_K8S) {
              //  String artifactPrepareVersionMavenDockerImage = script.commonPipelineEnvironment.configuration?.steps?.artifactPrepareVersion?.dockerImage?:""
                //artifactPrepareVersion script: script, buildTool: buildTool, maven: [dockerImage: artifactPrepareVersionMavenDockerImage]
            //} else
            if (config.inferBuildTool) {
                artifactPrepareVersion script: script, buildTool: buildTool
            } else {
                artifactSetVersion script: script
            }
        }

        if (Boolean.valueOf(env.ON_K8S) && config.containerMapResource) {
            ContainerMap.instance.initFromResource(script, config.containerMapResource, buildTool)
        }
        pipelineStashFilesBeforeBuild script: script
    }
}

private String checkBuildTool(script, config) {
    def buildDescriptorPattern = ''
    String buildTool

    if (config.inferBuildTool) {
        buildTool = script.commonPipelineEnvironment.buildTool
    } else {
        buildTool = config.buildTool
    }

    switch (buildTool) {
        case 'maven':
            buildDescriptorPattern = 'pom.xml'
            break
        case 'npm':
            buildDescriptorPattern = 'package.json'
            break
        case 'mta':
            buildDescriptorPattern = 'mta.yaml'
            break
    }
    if (buildDescriptorPattern && !findFiles(glob: buildDescriptorPattern)) {
        error "[${STEP_NAME}] buildTool configuration '${config.buildTool}' does not fit to your project, please set buildTool as genereal setting in your .pipeline/config.yml correctly, see also https://sap.github.io/jenkins-library/configuration/"
    }
    return buildTool
}

private void initStashConfiguration (script, stashSettings, verbose) {
    Map stashConfiguration = readYaml(text: libraryResource(stashSettings))
    if (verbose) echo "Stash config: ${stashConfiguration}"
    script.commonPipelineEnvironment.configuration.stageStashes = stashConfiguration
}

private void setGitUrlsOnCommonPipelineEnvironment(script, String gitUrl) {

    Map url = parseUrl(gitUrl)

    if (url.protocol in ['http', 'https']) {
        script.commonPipelineEnvironment.setGitSshUrl("git@${url.host}:${url.path}")
        script.commonPipelineEnvironment.setGitHttpsUrl(gitUrl)
    } else if (url.protocol in [ null, 'ssh', 'git']) {
        script.commonPipelineEnvironment.setGitSshUrl(gitUrl)
        script.commonPipelineEnvironment.setGitHttpsUrl("https://${url.host}/${url.path}")
    }

    List gitPathParts = url.path.replaceAll('.git', '').split('/')
    def gitFolder = 'N/A'
    def gitRepo = 'N/A'
    switch (gitPathParts.size()) {
        case 1:
            gitRepo = gitPathParts[0]
            break
        case 2:
            gitFolder = gitPathParts[0]
            gitRepo = gitPathParts[1]
            break
        case { it > 3 }:
            gitRepo = gitPathParts[gitPathParts.size()-1]
            gitPathParts.remove(gitPathParts.size()-1)
            gitFolder = gitPathParts.join('/')
            break
    }
    script.commonPipelineEnvironment.setGithubOrg(gitFolder)
    script.commonPipelineEnvironment.setGithubRepo(gitRepo)
}

/*
 * Returns the parts of an url.
 * Valid keys for the retured map are:
 *   - protocol
 *   - auth
 *   - host
 *   - port
 *   - path
 */
@NonCPS
/* private */ Map parseUrl(String url) {

    def urlMatcher = url =~ /^((http|https|git|ssh):\/\/)?((.*)@)?([^:\/]+)(:([\d]*))?(\/?(.*))$/

    return [
        protocol: urlMatcher[0][2],
        auth: urlMatcher[0][4],
        host: urlMatcher[0][5],
        port: urlMatcher[0][7],
        path: urlMatcher[0][9],
    ]
}

private void setPullRequestStageStepActivation(script, config, List actions) {

    if (script.commonPipelineEnvironment.configuration.runStep == null)
        script.commonPipelineEnvironment.configuration.runStep = [:]
    if (script.commonPipelineEnvironment.configuration.runStep[config.pullRequestStageName] == null)
        script.commonPipelineEnvironment.configuration.runStep[config.pullRequestStageName] = [:]

    actions.each {action ->
        if (action.startsWith(config.labelPrefix))
            action = action.minus(config.labelPrefix)

        def stepName = config.stepMappings[action]
        if (stepName) {
            script.commonPipelineEnvironment.configuration.runStep."${config.pullRequestStageName}"."${stepName}" = true
        }
    }
}
