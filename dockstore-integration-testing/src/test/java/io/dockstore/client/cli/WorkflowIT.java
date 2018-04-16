/*
 *    Copyright 2017 OICR
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.dockstore.client.cli;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

import io.dockstore.client.cli.nested.AbstractEntryClient;
import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.ConfidentialTest;
import io.dockstore.common.Registry;
import io.dockstore.common.SourceControl;
import io.dropwizard.testing.ResourceHelpers;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.ContainersApi;
import io.swagger.client.api.Ga4GhApi;
import io.swagger.client.api.UsersApi;
import io.swagger.client.api.WorkflowsApi;
import io.swagger.client.model.DockstoreTool;
import io.swagger.client.model.PublishRequest;
import io.swagger.client.model.SourceFile;
import io.swagger.client.model.Tool;
import io.swagger.client.model.User;
import io.swagger.client.model.Workflow;
import io.swagger.client.model.WorkflowVersion;
import org.apache.commons.dbutils.handlers.ScalarHandler;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.experimental.categories.Category;

import static io.dockstore.common.CommonTestUtilities.getTestingPostgres;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Extra confidential integration tests, focus on testing workflow interactions
 * {@link io.dockstore.client.cli.BaseIT}
 * @author dyuen
 */
@Category(ConfidentialTest.class)
public class WorkflowIT extends BaseIT {

    private static final String DOCKSTORE_TEST_USER2_HELLO_DOCKSTORE_WORKFLOW = SourceControl.GITHUB.toString() + "/DockstoreTestUser2/hello-dockstore-workflow";
    private static final String DOCKSTORE_TEST_USER2_DOCKSTORE_WORKFLOW = SourceControl.BITBUCKET.toString() + "/dockstore_testuser2/dockstore-workflow";
    private static final String DOCKSTORE_TEST_USER2_IMPORTS_DOCKSTORE_WORKFLOW = SourceControl.GITHUB.toString() + "/DockstoreTestUser2/dockstore-whalesay-imports";
    private static final String DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW = SourceControl.GITHUB.toString() + "/DockstoreTestUser2/dockstore_workflow_cnv";
    private static final String DOCKSTORE_TEST_USER2_NEXTFLOW_WORKFLOW = SourceControl.GITHUB.toString() + "/DockstoreTestUser2/rnatoy";

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();
    private static final String DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_TOOL = "DockstoreTestUser2/dockstore-cgpmap";

    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();

    @Before
    @Override
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
    }

    @Test
    public void testStubRefresh() throws ApiException {
        // need to promote user to admin to refresh all stubs
        final ApiClient webClient = getWebClient();
        UsersApi usersApi = new UsersApi(webClient);
        User user = usersApi.getUser();

        final List<Workflow> workflows = usersApi.refreshWorkflows(user.getId());
        for (Workflow workflow: workflows) {
            assertNotSame("", workflow.getWorkflowName());
        }

        assertTrue("workflow size was " + workflows.size(), workflows.size() > 1);
        assertTrue(
                "found non stub workflows " + workflows.stream().filter(workflow -> workflow.getMode() != Workflow.ModeEnum.STUB).count(),
                workflows.stream().allMatch(workflow -> workflow.getMode() == Workflow.ModeEnum.STUB));
    }

    @Test
    public void testTargettedRefresh() throws ApiException {
        // need to promote user to admin to refresh all stubs
        final CommonTestUtilities.TestingPostgres testingPostgres = getTestingPostgres();
        testingPostgres.runUpdateStatement("update enduser set isadmin = 't' where username = 'DockstoreTestUser2';");

        final ApiClient webClient = getWebClient();
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        UsersApi usersApi = new UsersApi(webClient);
        User user = usersApi.getUser();

        final List<Workflow> workflows = usersApi.refreshWorkflows(user.getId());

        for (Workflow workflow: workflows) {
            assertNotSame("", workflow.getWorkflowName());
        }

        // do targetted refresh, should promote workflow to fully-fleshed out workflow
        final Workflow workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_HELLO_DOCKSTORE_WORKFLOW);
        final Workflow refreshGithub = workflowApi.refresh(workflowByPathGithub.getId());
        final Workflow workflowByPathBitbucket = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_DOCKSTORE_WORKFLOW);
        final Workflow refreshBitbucket = workflowApi.refresh(workflowByPathBitbucket.getId());

        assertTrue("github workflow is not in full mode", refreshGithub.getMode() == Workflow.ModeEnum.FULL);
        assertTrue("github workflow version count is wrong: " + refreshGithub.getWorkflowVersions().size(),
                refreshGithub.getWorkflowVersions().size() == 4);
        assertTrue("should find two versions with files for github workflow, found : " + refreshGithub.getWorkflowVersions().stream()
                        .filter(workflowVersion -> !workflowVersion.getSourceFiles().isEmpty()).count(),
                refreshGithub.getWorkflowVersions().stream().filter(workflowVersion -> !workflowVersion.getSourceFiles().isEmpty()).count()
                        == 2);
        assertTrue("should find two valid versions for github workflow, found : " + refreshGithub.getWorkflowVersions().stream()
                        .filter(WorkflowVersion::isValid).count(),
                refreshGithub.getWorkflowVersions().stream().filter(WorkflowVersion::isValid).count() == 2);

        assertTrue("bitbucket workflow is not in full mode", refreshBitbucket.getMode() == Workflow.ModeEnum.FULL);

        assertTrue("bitbucket workflow version count is wrong: " + refreshBitbucket.getWorkflowVersions().size(),
                refreshBitbucket.getWorkflowVersions().size() == 5);
        assertTrue("should find 4 versions with files for bitbucket workflow, found : " + refreshBitbucket.getWorkflowVersions().stream()
                        .filter(workflowVersion -> !workflowVersion.getSourceFiles().isEmpty()).count(),
                refreshBitbucket.getWorkflowVersions().stream().filter(workflowVersion -> !workflowVersion.getSourceFiles().isEmpty())
                        .count() == 4);
        assertTrue("should find 4 valid versions for bitbucket workflow, found : " + refreshBitbucket.getWorkflowVersions().stream()
                        .filter(WorkflowVersion::isValid).count(),
                refreshBitbucket.getWorkflowVersions().stream().filter(WorkflowVersion::isValid).count() == 4);


    }

    @Test
    public void testWorkflowLaunchOrNotLaunchBasedOnCredentials() throws IOException {
        String toolpath = SourceControl.GITHUB.toString() + "/DockstoreTestUser2/md5sum-checker/test";
        final CommonTestUtilities.TestingPostgres testingPostgres = getTestingPostgres();
        testingPostgres.runUpdateStatement("update enduser set isadmin = 't' where username = 'DockstoreTestUser2';");
        final ApiClient webClient = getWebClient();
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        Workflow workflow = workflowApi
            .manualRegister(SourceControl.GITHUB.getFriendlyName(), "DockstoreTestUser2/md5sum-checker", "checker-workflow-wrapping-workflow.cwl",
                "test", "cwl", null);
        Workflow refresh = workflowApi.refresh(workflow.getId());

        Assert.assertTrue("workflow is already published for some reason", !refresh.isIsPublished());

        // should be able to launch properly with correct credentials even though the workflow is not published
        FileUtils.writeStringToFile(new File("md5sum.input"), "foo" , StandardCharsets.UTF_8);
        systemExit.expectSystemExitWithStatus(0);
        Client.main(new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "launch", "--entry", toolpath, "--json" , ResourceHelpers.resourceFilePath("md5sum-wrapper-tool.json") ,  "--script" });

        // should not be able to launch properly with incorrect credentials
        systemExit.expectSystemExitWithStatus(Client.ENTRY_NOT_FOUND);
        Client.main(new String[] { "--config", ResourceHelpers.resourceFilePath("config_file.txt"), "workflow", "launch", "--entry", toolpath, "--json" , ResourceHelpers.resourceFilePath("md5sum-wrapper-tool.json") ,  "--script" });
    }

    @Test
    public void testNextFlowRefresh() throws ApiException {
        // need to promote user to admin to refresh all stubs
        final CommonTestUtilities.TestingPostgres testingPostgres = getTestingPostgres();
        testingPostgres.runUpdateStatement("update enduser set isadmin = 't' where username = 'DockstoreTestUser2';");

        final ApiClient webClient = getWebClient();
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        UsersApi usersApi = new UsersApi(webClient);
        User user = usersApi.getUser();

        final List<Workflow> workflows = usersApi.refreshWorkflows(user.getId());

        for (Workflow workflow: workflows) {
            assertNotSame("", workflow.getWorkflowName());
        }

        // do targetted refresh, should promote workflow to fully-fleshed out workflow
        Workflow workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_NEXTFLOW_WORKFLOW);
        // need to set paths properly
        workflowByPathGithub.setWorkflowPath("/nextflow.config");
        workflowByPathGithub.setDescriptorType(AbstractEntryClient.Type.NEXTFLOW.toString());
        workflowApi.updateWorkflow(workflowByPathGithub.getId(), workflowByPathGithub);

        workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_NEXTFLOW_WORKFLOW);
        final Workflow refreshGithub = workflowApi.refresh(workflowByPathGithub.getId());

        assertTrue("github workflow is not in full mode", refreshGithub.getMode() == Workflow.ModeEnum.FULL);
        assertTrue("github workflow version count is wrong: " + refreshGithub.getWorkflowVersions().size(),
            refreshGithub.getWorkflowVersions().size() == 12);
        assertTrue("should find 12 versions with files for github workflow, found : " + refreshGithub.getWorkflowVersions().stream()
                .filter(workflowVersion -> !workflowVersion.getSourceFiles().isEmpty()).count(),
            refreshGithub.getWorkflowVersions().stream().filter(workflowVersion -> !workflowVersion.getSourceFiles().isEmpty()).count()
                == 12);
        assertTrue("should find 12 valid versions for github workflow, found : " + refreshGithub.getWorkflowVersions().stream()
                .filter(WorkflowVersion::isValid).count(),
            refreshGithub.getWorkflowVersions().stream().filter(WorkflowVersion::isValid).count() == 12);

        // nextflow version should have
        assertTrue("should find 2 files for each version for now: " + refreshGithub.getWorkflowVersions().stream()
                .filter(workflowVersion -> workflowVersion.getSourceFiles().size() != 2).count(),
            refreshGithub.getWorkflowVersions().stream().noneMatch(workflowVersion -> workflowVersion.getSourceFiles().size() != 2));
    }

    /**
     * This test checks that a user can successfully refresh their workflows (only stubs)
     *
     * @throws ApiException
     */
    @Test
    public void testRefreshAllForAUser() throws ApiException {
        final CommonTestUtilities.TestingPostgres testingPostgres = getTestingPostgres();
        testingPostgres.runUpdateStatement("update enduser set isadmin = 't' where username = 'DockstoreTestUser2';");
        long userId = 1;

        final ApiClient webClient = getWebClient();
        UsersApi usersApi = new UsersApi(webClient);
        final List<Workflow> workflow = usersApi.refreshWorkflows(userId);

        // Check that there are multiple workflows
        final long count = testingPostgres.runSelectStatement("select count(*) from workflow", new ScalarHandler<>());
        assertTrue("Workflow entries should exist", count > 0);

        // Check that there are only stubs (no workflow version)
        final long count2 = testingPostgres.runSelectStatement("select count(*) from workflowversion", new ScalarHandler<>());
        assertTrue("No entries in workflowversion", count2 == 0);
        final long count3 = testingPostgres
                .runSelectStatement("select count(*) from workflow where mode = '" + Workflow.ModeEnum.FULL + "'", new ScalarHandler<>());
        assertTrue("No workflows are in full mode", count3 == 0);

        // check that a nextflow workflow made it
        long nfWorkflowCount = workflow.stream().filter(w -> w.getGitUrl().contains("mta-nf")).count();
        assertTrue("Nextflow workflow not found", nfWorkflowCount > 0);
        Workflow mtaNf = workflow.stream().filter(w -> w.getGitUrl().contains("mta-nf")).findFirst().get();
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        mtaNf.setWorkflowPath("/nextflow.config");
        mtaNf.setDescriptorType(SourceFile.TypeEnum.NEXTFLOW.toString());
        workflowApi.updateWorkflow(mtaNf.getId(), mtaNf);
        workflowApi.refresh(mtaNf.getId());
        // publish this way? (why is the auto-generated variable private?)
        workflowApi.publish(mtaNf.getId(), new PublishRequest(){
            @Override
            public Boolean isPublish() {
                return true;
            }
        });
        mtaNf = workflowApi.getWorkflow(mtaNf.getId());
        assertTrue("Nextflow workflow not found after update", mtaNf != null);
        assertTrue("nextflow workflow should have at least two versions", mtaNf.getWorkflowVersions().size() >= 2);
        int numOfSourceFiles = mtaNf.getWorkflowVersions().stream().mapToInt(version -> version.getSourceFiles().size()).sum();
        assertTrue("nextflow workflow should have at least two sourcefiles", numOfSourceFiles >= 2);
        long scriptCount = mtaNf.getWorkflowVersions().stream()
            .mapToLong(version -> version.getSourceFiles().stream().filter(file -> file.getType() == SourceFile.TypeEnum.NEXTFLOW).count()).sum();
        long configCount = mtaNf.getWorkflowVersions().stream()
            .mapToLong(version -> version.getSourceFiles().stream().filter(file -> file.getType() == SourceFile.TypeEnum.NEXTFLOW_CONFIG).count()).sum();
        assertTrue("nextflow workflow should have at least one config file and one script file", scriptCount >= 1 && configCount >= 1);

        // check that we can pull down the nextflow workflow via the ga4gh TRS API
        Ga4GhApi ga4Ghv2Api = new Ga4GhApi(webClient);
        List<Tool> toolV2s = ga4Ghv2Api.toolsGet(null, null, null, null, null, null, null, null, null);
        String mtaWorkflowID = "#workflow/github.com/DockstoreTestUser2/mta-nf";
        Tool toolV2 = ga4Ghv2Api.toolsIdGet(mtaWorkflowID);
        assertTrue("could get mta as part of list", toolV2s.size() > 0 && toolV2s.stream().anyMatch(tool -> Objects
            .equals(tool.getId(), mtaWorkflowID)));
        assertTrue("could get mta as a specific tool", toolV2 != null);
    }

    /**
     * This test does not use admin rights, note that a number of operations go through the UserApi to get this to work
     *
     * @throws ApiException
     */
    @Test
    public void testPublishingAndListingOfPublished() throws ApiException {
        final ApiClient webClient = getWebClient();
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        // should start with nothing published
        assertTrue("should start with nothing published ", workflowApi.allPublishedWorkflows().isEmpty());
        // refresh just for the current user
        UsersApi usersApi = new UsersApi(webClient);
        final Long userId = usersApi.getUser().getId();
        usersApi.refreshWorkflows(userId);
        assertTrue("should remain with nothing published ", workflowApi.allPublishedWorkflows().isEmpty());
        // assertTrue("should have a bunch of stub workflows: " +  usersApi..allWorkflows().size(), workflowApi.allWorkflows().size() == 4);

        final Workflow workflowByPath = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_HELLO_DOCKSTORE_WORKFLOW);
        // refresh targeted
        workflowApi.refresh(workflowByPath.getId());

        // publish one
        final PublishRequest publishRequest = SwaggerUtility.createPublishRequest(true);
        workflowApi.publish(workflowByPath.getId(), publishRequest);
        assertTrue("should have one published, found  " + workflowApi.allPublishedWorkflows().size(),
                workflowApi.allPublishedWorkflows().size() == 1);
        final Workflow publishedWorkflow = workflowApi.getPublishedWorkflow(workflowByPath.getId());
        assertTrue("did not get published workflow", publishedWorkflow != null);
        final Workflow publishedWorkflowByPath = workflowApi.getPublishedWorkflowByPath(DOCKSTORE_TEST_USER2_HELLO_DOCKSTORE_WORKFLOW);
        assertTrue("did not get published workflow", publishedWorkflowByPath != null);
    }

    /**
     * Tests manual registration and publishing of a github and bitbucket workflow
     *
     * @throws ApiException
     */
    @Test
    public void testManualRegisterThenPublish() throws ApiException {
        final ApiClient webClient = getWebClient();
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        UsersApi usersApi = new UsersApi(webClient);
        final Long userId = usersApi.getUser().getId();

        // Make publish request (true)
        final PublishRequest publishRequest = SwaggerUtility.createPublishRequest(true);

        // Set up postgres
        final CommonTestUtilities.TestingPostgres testingPostgres = getTestingPostgres();

        // Get workflows
        usersApi.refreshWorkflows(userId);

        // Manually register workflow github
        Workflow githubWorkflow = workflowApi
                .manualRegister("github", "DockstoreTestUser2/hello-dockstore-workflow", "/Dockstore.wdl", "altname", "wdl", "/test.json");

        // Manually register workflow bitbucket
        Workflow bitbucketWorkflow = workflowApi
                .manualRegister("bitbucket", "dockstore_testuser2/dockstore-workflow", "/Dockstore.cwl", "altname", "cwl", "/test.json");

        // Assert some things
        final long count = testingPostgres
                .runSelectStatement("select count(*) from workflow where mode = '" + Workflow.ModeEnum.FULL + "'", new ScalarHandler<>());
        assertTrue("No workflows are in full mode", count == 0);
        final long count2 = testingPostgres
                .runSelectStatement("select count(*) from workflow where workflowname = 'altname'", new ScalarHandler<>());
        assertTrue("There should be two workflows with name altname, there are " + count2, count2 == 2);

        // Publish github workflow
        workflowApi.refresh(githubWorkflow.getId());
        workflowApi.publish(githubWorkflow.getId(), publishRequest);

        // Publish bitbucket workflow
        workflowApi.refresh(bitbucketWorkflow.getId());
        workflowApi.publish(bitbucketWorkflow.getId(), publishRequest);

        // Assert some things
        assertTrue("should have two published, found  " + workflowApi.allPublishedWorkflows().size(),
                workflowApi.allPublishedWorkflows().size() == 2);
        final long count3 = testingPostgres
                .runSelectStatement("select count(*) from workflow where mode = '" + Workflow.ModeEnum.FULL + "'", new ScalarHandler<>());
        assertTrue("Two workflows are in full mode", count3 == 2);
        final long count4 = testingPostgres
                .runSelectStatement("select count(*) from workflowversion where valid = 't'", new ScalarHandler<>());
        assertTrue("There should be 5 valid version tags, there are " + count4, count4 == 6);
    }


    /**
     * Tests manual registration of a tool and check that descriptors are downloaded properly.
     * Description is pulled properly from an $include.
     *
     * @throws IOException
     * @throws TimeoutException
     * @throws ApiException
     */
    @Test
    public void testManualRegisterToolWithMixinsAndSymbolicLinks() throws ApiException {
        final ApiClient webClient = getWebClient();
        ContainersApi toolApi = new ContainersApi(webClient);

        DockstoreTool tool = new DockstoreTool();
        tool.setDefaultCwlPath("/cwls/cgpmap-bamOut.cwl");
        tool.setGitUrl("git@github.com:DockstoreTestUser2/dockstore-cgpmap.git");
        tool.setNamespace("dockstoretestuser2");
        tool.setName("dockstore-cgpmap");
        tool.setRegistryString(Registry.QUAY_IO.toString());
        tool.setDefaultVersion("symbolic.v1");

        DockstoreTool registeredTool = toolApi.registerManual(tool);
        registeredTool = toolApi.refresh(registeredTool.getId());

        // Make publish request (true)
        final PublishRequest publishRequest = SwaggerUtility.createPublishRequest(true);
        toolApi.publish(registeredTool.getId(), publishRequest);

        assertTrue("did not pick up description from $include", registeredTool.getDescription().contains("A Docker container for PCAP-core."));
        assertTrue("did not import mixin and includes properly", registeredTool.getTags().stream().filter(tag -> Objects
            .equals(tag.getName(), "test.v1")).findFirst().get().getSourceFiles().size() == 5);
        assertTrue("did not import symbolic links to folders properly", registeredTool.getTags().stream().filter(tag -> Objects
            .equals(tag.getName(), "symbolic.v1")).findFirst().get().getSourceFiles().size() == 5);
    }

    /**
     * Tests that trying to register a duplicate workflow fails, and that registering a non-existant repository failes
     *
     * @throws ApiException
     */
    @Test
    public void testManualRegisterErrors() throws ApiException {
        final ApiClient webClient = getWebClient();
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        UsersApi usersApi = new UsersApi(webClient);
        final Long userId = usersApi.getUser().getId();

        // Get workflows
        usersApi.refreshWorkflows(userId);

        // Manually register workflow
        boolean success = true;
        try {
            workflowApi.manualRegister("github", "DockstoreTestUser2/hello-dockstore-workflow", "/Dockstore.wdl", "", "wdl", "/test.json");
        } catch (ApiException c) {
            success = false;
        } finally {
            assertTrue("The workflow cannot be registered as it is a duplicate.", !success);
        }

        success = true;
        try {
            workflowApi.manualRegister("github", "dasn/iodnasiodnasio", "/Dockstore.wdl", "", "wdl", "/test.json");
        } catch (ApiException c) {
            success = false;
        } finally {
            assertTrue("The workflow cannot be registered as the repository doesn't exist.", !success);
        }
    }

    @Test
    public void testSecondaryFileOperations() throws ApiException {
        final ApiClient webClient = getWebClient();
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        workflowApi.manualRegister("github", "DockstoreTestUser2/dockstore-whalesay-imports", "/Dockstore.cwl", "", "cwl", "/test.json");
        final Workflow workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_IMPORTS_DOCKSTORE_WORKFLOW);

        // This checks if a workflow whose default name was manually registered as an empty string would become null
        assertNull(workflowByPathGithub.getWorkflowName());

        final Workflow workflow = workflowApi.refresh(workflowByPathGithub.getId());

        // This checks if a workflow whose default name is null would remain as null after refresh
        assertNull(workflow.getWorkflowName());

        // test out methods to access secondary files
        final List<SourceFile> masterImports = workflowApi.secondaryCwl(workflow.getId(), "master");
        assertTrue("should find 2 imports, found " + masterImports.size(), masterImports.size() == 2);
        final SourceFile master = workflowApi.cwl(workflow.getId(), "master");
        assertTrue("master content incorrect", master.getContent().contains("untar") && master.getContent().contains("compile"));

        // get secondary files by path
        SourceFile argumentsTool = workflowApi.secondaryCwlPath(workflow.getId(), "arguments.cwl", "master");
        assertTrue("argumentstool content incorrect", argumentsTool.getContent().contains("Example trivial wrapper for Java 7 compiler"));
    }

    @Test
    public void testRelativeSecondaryFileOperations() throws ApiException {
        final ApiClient webClient = getWebClient();
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        workflowApi.manualRegister("github", "DockstoreTestUser2/dockstore_workflow_cnv", "/workflow/cnv.cwl", "", "cwl", "/test.json");
        final Workflow workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW);

        // This checks if a workflow whose default name was manually registered as an empty string would become null
        assertNull(workflowByPathGithub.getWorkflowName());

        final Workflow workflow = workflowApi.refresh(workflowByPathGithub.getId());

        // This checks if a workflow whose default name is null would remain as null after refresh
        assertNull(workflow.getWorkflowName());

        // test out methods to access secondary files

        final List<SourceFile> masterImports = workflowApi.secondaryCwl(workflow.getId(), "master");
        assertTrue("should find 3 imports, found " + masterImports.size(), masterImports.size() == 3);
        final List<SourceFile> rootImports = workflowApi.secondaryCwl(workflow.getId(), "rootTest");
        assertTrue("should find 0 imports, found " + rootImports.size(), rootImports.size() == 0);

        // next, change a path for the root imports version
        List<WorkflowVersion> workflowVersions = workflow.getWorkflowVersions();
        workflowVersions.stream().filter(v -> v.getName().equals("rootTest")).findFirst().get().setWorkflowPath("/cnv.cwl");
        workflowApi.updateWorkflowVersion(workflow.getId(), workflowVersions);
        workflowApi.refresh(workflowByPathGithub.getId());
        final List<SourceFile> newMasterImports = workflowApi.secondaryCwl(workflow.getId(), "master");
        assertTrue("should find 3 imports, found " + newMasterImports.size(), newMasterImports.size() == 3);
        final List<SourceFile> newRootImports = workflowApi.secondaryCwl(workflow.getId(), "rootTest");
        assertTrue("should find 3 imports, found " + newRootImports.size(), newRootImports.size() == 3);
    }
}
