import com.sap.piper.JenkinsUtils
import com.sap.piper.integration.TransportManagementService

import hudson.AbortException

import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.RuleChain
import util.*
import util.JenkinsReadYamlRule

import static org.hamcrest.Matchers.containsString
import static org.hamcrest.Matchers.is
import static org.hamcrest.Matchers.not
import static org.junit.Assert.assertThat

public class TmsUploadTest extends BasePiperTest {

    private ExpectedException thrown = new ExpectedException()
    private JenkinsStepRule stepRule = new JenkinsStepRule(this)
    private JenkinsLoggingRule loggingRule = new JenkinsLoggingRule(this)
    private JenkinsEnvironmentRule envRule = new JenkinsEnvironmentRule(this)
    private JenkinsReadFileRule readFileRule = new JenkinsReadFileRule(this, 'test/resources/TransportManagementService')
    private JenkinsFileExistsRule fileExistsRules = new JenkinsFileExistsRule(this, ['dummy.mtar', 'mta.yaml', 'dummy.mtaext', 'dummy2.mtaext', 'invalidDummy.mtaext'])
	private JenkinsReadYamlRule readYamlRule = new JenkinsReadYamlRule(this)

    def tmsStub
    def jenkinsUtilsStub
    def calledTmsMethodsWithArgs = []
    def uri = "https://dummy-url.com"
    def uaaUrl = "https://oauth.com"
    def oauthClientId = "myClientId"
    def oauthClientSecret = "myClientSecret"
    def serviceKeyContent = """{ 
                                "uri": "${uri}",
                                "uaa": {
                                    "clientid": "${oauthClientId}",
                                    "clientsecret": "${oauthClientSecret}",
                                    "url": "${uaaUrl}"
                                }
                               }
                             """
	
    class JenkinsUtilsMock extends JenkinsUtils {
        def userId

        JenkinsUtilsMock(userId) {
            this.userId = userId
        }

        def getJobStartedByUserId(){
            return this.userId
        }
    }

    @Rule
    public RuleChain ruleChain = Rules.getCommonRules(this)
        .around(thrown)
        .around(readYamlRule)
        .around(stepRule)
        .around(loggingRule)
        .around(envRule)
        .around(fileExistsRules)
        .around(new JenkinsCredentialsRule(this)
            .withCredentials('TMS_ServiceKey', serviceKeyContent))

    @Before
    public void setup() {
        tmsStub = mockTransportManagementService()
        helper.registerAllowedMethod("unstash", [String.class], { s -> return [s] })
		readYamlRule.registerYaml("mta.yaml", new FileInputStream(new File("test/resources/TransportManagementService/mta.yaml")))
					.registerYaml("dummy.mtaext", new FileInputStream(new File("test/resources/TransportManagementService/dummy.mtaext")))
					.registerYaml("dummy2.mtaext", new FileInputStream(new File("test/resources/TransportManagementService/dummy2.mtaext")))
					.registerYaml("invalidDummy.mtaext", new FileInputStream(new File("test/resources/TransportManagementService/invalidDummy.mtaext")))
    }

    @After
    void tearDown() {
        calledTmsMethodsWithArgs.clear()
    }

    @Test
    public void minimalConfig__isSuccessful() {
        jenkinsUtilsStub = new JenkinsUtilsMock("Test User")
        binding.workspace = "."
        envRule.env.gitCommitId = "testCommitId"

        stepRule.step.tmsUpload(
            script: nullScript,
            juStabUtils: utils,
            jenkinsUtilsStub: jenkinsUtilsStub,
            transportManagementService: tmsStub,
            mtaPath: 'dummy.mtar',
            nodeName: 'myNode',
            credentialsId: 'TMS_ServiceKey'
        )

        assertThat(calledTmsMethodsWithArgs[0], is("authentication('${uaaUrl}', '${oauthClientId}', '${oauthClientSecret}')"))
        assertThat(calledTmsMethodsWithArgs[1], is("uploadFile('${uri}', 'myToken', './dummy.mtar', 'Test User')"))
        assertThat(calledTmsMethodsWithArgs[2], is("uploadFileToNode('${uri}', 'myToken', 'myNode', '1234', 'Git CommitId: testCommitId')"))
        assertThat(loggingRule.log, containsString("[TransportManagementService] File './dummy.mtar' successfully uploaded to Node 'myNode' (Id: '1000')."))
        assertThat(loggingRule.log, containsString("[TransportManagementService] Corresponding Transport Request: 'Git CommitId: testCommitId' (Id: '2000')"))
        assertThat(loggingRule.log, not(containsString("[TransportManagementService] CredentialsId: 'TMS_ServiceKey'")))

    }

    @Test
    public void verboseMode__yieldsMoreEchos() {
        jenkinsUtilsStub = new JenkinsUtilsMock("Test User")
        binding.workspace = "."
        envRule.env.gitCommitId = "testCommitId"

        stepRule.step.tmsUpload(
            script: nullScript,
            juStabUtils: utils,
            jenkinsUtilsStub: jenkinsUtilsStub,
            transportManagementService: tmsStub,
            mtaPath: 'dummy.mtar',
            nodeName: 'myNode',
            credentialsId: 'TMS_ServiceKey',
            verbose: true
        )

        assertThat(loggingRule.log, containsString("[TransportManagementService] CredentialsId: 'TMS_ServiceKey'"))
        assertThat(loggingRule.log, containsString("[TransportManagementService] Node name: 'myNode'"))
        assertThat(loggingRule.log, containsString("[TransportManagementService] MTA path: 'dummy.mtar'"))
        assertThat(loggingRule.log, containsString("[TransportManagementService] Named user: 'Test User'"))
        assertThat(loggingRule.log, containsString("[TransportManagementService] UAA URL: '${uaaUrl}'"))
        assertThat(loggingRule.log, containsString("[TransportManagementService] TMS URL: '${uri}'"))
        assertThat(loggingRule.log, containsString("[TransportManagementService] ClientId: '${oauthClientId}'"))
    }

    @Test
    public void noUserAvailableInCurrentBuild__usesDefaultUser() {
        jenkinsUtilsStub = new JenkinsUtilsMock(null)
        binding.workspace = "."
        envRule.env.gitCommitId = "testCommitId"

        stepRule.step.tmsUpload(
            script: nullScript,
            juStabUtils: utils,
            jenkinsUtilsStub: jenkinsUtilsStub,
            transportManagementService: tmsStub,
            mtaPath: 'dummy.mtar',
            nodeName: 'myNode',
            credentialsId: 'TMS_ServiceKey'
        )

        assertThat(calledTmsMethodsWithArgs[1], is("uploadFile('${uri}', 'myToken', './dummy.mtar', 'Piper-Pipeline')"))
    }

    @Test
    public void addCustomDescription__descriptionChanged() {
        jenkinsUtilsStub = new JenkinsUtilsMock("Test User")
        binding.workspace = "."
        envRule.env.gitCommitId = "testCommitId"

        stepRule.step.tmsUpload(
            script: nullScript,
            juStabUtils: utils,
            jenkinsUtilsStub: jenkinsUtilsStub,
            transportManagementService: tmsStub,
            mtaPath: 'dummy.mtar',
            nodeName: 'myNode',
            credentialsId: 'TMS_ServiceKey',
            customDescription: 'My custom description for testing.'
        )

        assertThat(calledTmsMethodsWithArgs[2], is("uploadFileToNode('${uri}', 'myToken', 'myNode', '1234', 'My custom description for testing.')"))
        assertThat(loggingRule.log, containsString("[TransportManagementService] Corresponding Transport Request: 'My custom description for testing.' (Id: '2000')"))
    }
	
	@Test
	public void uploadMtaExtensionDescriptor__isSuccessful() {
		Map nodeExtDescriptorMap = ["testNode1": "dummy.mtaext", "testNode2": "dummy2.mtaext"]
		
		jenkinsUtilsStub = new JenkinsUtilsMock("Test User")
		binding.workspace = "."
		envRule.env.gitCommitId = "testCommitId"

		stepRule.step.tmsUpload(
			script: nullScript,
			juStabUtils: utils,
			jenkinsUtilsStub: jenkinsUtilsStub,
			transportManagementService: tmsStub,
			mtaPath: 'dummy.mtar',
			nodeName: 'myNode',
			credentialsId: 'TMS_ServiceKey',
			nodeExtDescriptorMapping: nodeExtDescriptorMap,
			mtaVersion: '0.0.1',
		)

		assertThat(loggingRule.log, containsString("[TransportManagementService] MTA Extention Descriptor './dummy.mtaext' (Id: '1') successfully uploaded to Node with id '1'."))
		assertThat(calledTmsMethodsWithArgs[2], is("uploadMtaExtDescriptorToNode('${uri}', 'myToken', 1, './dummy.mtaext', '0.0.1', 'Git CommitId: testCommitId', 'Test User')"))
		assertThat(loggingRule.log, containsString("[TransportManagementService] MTA Extention Descriptor './dummy2.mtaext' (Id: '2') successfully uploaded to Node with id '2'."))
		assertThat(calledTmsMethodsWithArgs[3], is("uploadMtaExtDescriptorToNode('${uri}', 'myToken', 2, './dummy2.mtaext', '0.0.1', 'Git CommitId: testCommitId', 'Test User')"))
	}

    @Test
    public void failOnMissingMtaFile() {

        thrown.expect(AbortException)
        thrown.expectMessage('Mta file \'dummy.mtar\' does not exist.')

        fileExistsRules.existingFiles.remove('dummy.mtar')
        jenkinsUtilsStub = new JenkinsUtilsMock("Test User")

        stepRule.step.tmsUpload(
            script: nullScript,
            juStabUtils: utils,
            jenkinsUtilsStub: jenkinsUtilsStub,
            transportManagementService: tmsStub,
            mtaPath: 'dummy.mtar',
            nodeName: 'myNode',
            credentialsId: 'TMS_ServiceKey',
            customDescription: 'My custom description for testing.'
        )
    }

    @Test
    public void failOnInvalidNodeExtDescriptorMapping() {
    	thrown.expect(AbortException)
    	thrown.expectMessage("MTA extension descriptor files [notexisted.mtaext, notexisted2.mtaext] don't exist.")
		thrown.expectMessage("Nodes [testNode3, testNode4] don't exist. Please check the node name or creat these nodes.")
		thrown.expectMessage("MTA ID in MTA extension descriptor files [invalidDummy.mtaext] is incorrect. ")
		
		// test on all kinds of errors: node doesn't exist, MTA ID in .mtaext is incorrect, and .mtaext file doesn't exist
    	Map nodeExtDescriptorMap = ["testNode1": "invalidDummy.mtaext", "testNode3": "notexisted.mtaext", "testNode4": "notexisted2.mtaext"]
    			
		jenkinsUtilsStub = new JenkinsUtilsMock("Test User")
    			
    	stepRule.step.tmsUpload(
    		script: nullScript,
    		juStabUtils: utils,
    		jenkinsUtilsStub: jenkinsUtilsStub,
    		transportManagementService: tmsStub,
    		mtaPath: 'dummy.mtar',
    		nodeName: 'myNode',
    		credentialsId: 'TMS_ServiceKey',
    		nodeExtDescriptorMapping: nodeExtDescriptorMap,
    		mtaVersion: '0.0.1',
    	)
    }
    
    def mockTransportManagementService() {
        return new TransportManagementService(nullScript, [:]) {
            def authentication(String uaaUrl, String oauthClientId, String oauthClientSecret) {
                calledTmsMethodsWithArgs << "authentication('${uaaUrl}', '${oauthClientId}', '${oauthClientSecret}')"
                return "myToken"
            }

            def uploadFile(String url, String token, String file, String namedUser) {
                calledTmsMethodsWithArgs << "uploadFile('${url}', '${token}', '${file}', '${namedUser}')"
                return [fileId: 1234, fileName: file]
            }

            def uploadFileToNode(String url, String token, String nodeName, int fileId, String description, String namedUser) {
                calledTmsMethodsWithArgs << "uploadFileToNode('${url}', '${token}', '${nodeName}', '${fileId}', '${description}')"
                return [transportRequestDescription: description, transportRequestId: 2000, queueEntries: [nodeName: 'myNode', nodeId: 1000]]
            }
			
			def uploadMtaExtDescriptorToNode(String url, String token, Long nodeId, String file, String mtaVersion, String description, String namedUser) {
				calledTmsMethodsWithArgs << "uploadMtaExtDescriptorToNode('${url}', '${token}', ${nodeId}, '${file}', '${mtaVersion}', '${description}', '${namedUser}')"
				return [fileId: nodeId, fileName: file]
			}
			
			def getNodes(String url, String token) {
				calledTmsMethodsWithArgs << "getNodes('${url}', '${token}')"
				return [nodes: [[id: 1, name: "testNode1"], [id: 2, name: "testNode2"]]]
			}
        }
    }
}
