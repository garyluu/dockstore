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

package io.dockstore.webservice.resources;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;

import com.codahale.metrics.annotation.Timed;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import io.dockstore.common.SourceControl;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.api.PublishRequest;
import io.dockstore.webservice.api.StarRequest;
import io.dockstore.webservice.api.VerifyRequest;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.SourceFile.FileType;
import io.dockstore.webservice.core.Token;
import io.dockstore.webservice.core.TokenType;
import io.dockstore.webservice.core.Tool;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.WorkflowMode;
import io.dockstore.webservice.core.WorkflowVersion;
import io.dockstore.webservice.doi.DOIGeneratorFactory;
import io.dockstore.webservice.doi.DOIGeneratorInterface;
import io.dockstore.webservice.helpers.BitBucketSourceCodeRepo;
import io.dockstore.webservice.helpers.ElasticManager;
import io.dockstore.webservice.helpers.ElasticMode;
import io.dockstore.webservice.helpers.EntryLabelHelper;
import io.dockstore.webservice.helpers.EntryVersionHelper;
import io.dockstore.webservice.helpers.GitHubSourceCodeRepo;
import io.dockstore.webservice.helpers.GitLabSourceCodeRepo;
import io.dockstore.webservice.helpers.SourceCodeRepoFactory;
import io.dockstore.webservice.helpers.SourceCodeRepoInterface;
import io.dockstore.webservice.jdbi.EntryDAO;
import io.dockstore.webservice.jdbi.FileDAO;
import io.dockstore.webservice.jdbi.LabelDAO;
import io.dockstore.webservice.jdbi.TokenDAO;
import io.dockstore.webservice.jdbi.ToolDAO;
import io.dockstore.webservice.jdbi.UserDAO;
import io.dockstore.webservice.jdbi.WorkflowDAO;
import io.dockstore.webservice.jdbi.WorkflowVersionDAO;
import io.dockstore.webservice.languages.LanguageHandlerFactory;
import io.dockstore.webservice.languages.LanguageHandlerInterface;
import io.dropwizard.auth.Auth;
import io.dropwizard.hibernate.UnitOfWork;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import io.swagger.model.DescriptorType;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.dockstore.client.cli.ArgumentUtility.CWL_STRING;
import static io.dockstore.client.cli.ArgumentUtility.WDL_STRING;
import static io.dockstore.webservice.Constants.JWT_SECURITY_DEFINITION_NAME;

/**
 * @author dyuen
 */
@Path("/workflows")
@Api("workflows")
@Produces(MediaType.APPLICATION_JSON)
public class WorkflowResource implements AuthenticatedResourceInterface, EntryVersionHelper<Workflow>, StarrableResourceInterface, SourceControlResourceInterface {
    private static final String CWL_CHECKER = "_cwl_checker";
    private static final String WDL_CHECKER = "_wdl_checker";
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowResource.class);
    private final ElasticManager elasticManager;
    private final UserDAO userDAO;
    private final TokenDAO tokenDAO;
    private final WorkflowDAO workflowDAO;
    private final ToolDAO toolDAO;
    private final WorkflowVersionDAO workflowVersionDAO;
    private final LabelDAO labelDAO;
    private final FileDAO fileDAO;
    private final HttpClient client;

    private final String bitbucketClientID;
    private final String bitbucketClientSecret;

    @SuppressWarnings("checkstyle:parameternumber")
    public WorkflowResource(HttpClient client, UserDAO userDAO, TokenDAO tokenDAO, ToolDAO toolDAO, WorkflowDAO workflowDAO,
        WorkflowVersionDAO workflowVersionDAO, LabelDAO labelDAO, FileDAO fileDAO, String bitbucketClientID, String bitbucketClientSecret) {
        this.userDAO = userDAO;
        this.tokenDAO = tokenDAO;
        this.workflowVersionDAO = workflowVersionDAO;
        this.toolDAO = toolDAO;
        this.labelDAO = labelDAO;
        this.fileDAO = fileDAO;
        this.client = client;

        this.bitbucketClientID = bitbucketClientID;
        this.bitbucketClientSecret = bitbucketClientSecret;

        this.workflowDAO = workflowDAO;
        elasticManager = new ElasticManager();
    }

    /**
     * TODO: this should not be a GET either
     * @param user
     * @param workflowId
     * @return
     */
    @GET
    @Path("/{workflowId}/restub")
    @Timed
    @UnitOfWork
    @ApiOperation(value = "Restub a workflow", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Restubs a full, unpublished workflow.", response = Workflow.class)
    public Workflow restub(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "workflow ID", required = true) @PathParam("workflowId") Long workflowId) {
        Workflow workflow = workflowDAO.findById(workflowId);
        // Check that workflow is valid to restub
        if (workflow.getIsPublished()) {
            throw new CustomWebApplicationException("A workflow must be unpublished to restub.", HttpStatus.SC_BAD_REQUEST);
        }

        workflow.setMode(WorkflowMode.STUB);

        // go through and delete versions for a stub
        for (WorkflowVersion version : workflow.getVersions()) {
            workflowVersionDAO.delete(version);
        }
        workflow.getVersions().clear();

        // Do we maintain the checker workflow association? For now we won't
        workflow.setCheckerWorkflow(null);


        elasticManager.handleIndexUpdate(workflow, ElasticMode.DELETE);
        return workflow;

    }

    /**
     * For each valid token for a git hosting service, refresh all workflows
     *
     * @param user         a user to refresh workflows for
     * @param organization limit the refresh to particular organizations if given
     * @param alreadyProcessed     skip particular workflows if already refreshed, previously used for debugging
     */
    void refreshStubWorkflowsForUser(User user, String organization, Set<Long> alreadyProcessed) {

        List<Token> tokens = checkOnBitbucketToken(user);
        // Check if tokens for git hosting services are valid and refresh corresponding workflows
        // Refresh Bitbucket
        Token bitbucketToken = Token.extractToken(tokens, TokenType.BITBUCKET_ORG.toString());
        // Refresh Github
        Token githubToken = Token.extractToken(tokens, TokenType.GITHUB_COM.toString());
        // Refresh Gitlab
        Token gitlabToken = Token.extractToken(tokens, TokenType.GITLAB_COM.toString());

        // create each type of repo and check its validity
        BitBucketSourceCodeRepo bitBucketSourceCodeRepo = null;
        if (bitbucketToken != null) {
            bitBucketSourceCodeRepo = new BitBucketSourceCodeRepo(bitbucketToken.getUsername(), client, bitbucketToken.getContent(), null);
            bitBucketSourceCodeRepo.checkSourceCodeValidity();
        }

        GitHubSourceCodeRepo gitHubSourceCodeRepo = null;
        if (githubToken != null) {
            gitHubSourceCodeRepo = new GitHubSourceCodeRepo(user.getUsername(), githubToken.getContent(), null);
            gitHubSourceCodeRepo.checkSourceCodeValidity();
        }

        GitLabSourceCodeRepo gitLabSourceCodeRepo = null;
        if (gitlabToken != null) {
            gitLabSourceCodeRepo = new GitLabSourceCodeRepo(user.getUsername(), client, gitlabToken.getContent(), null);
            gitLabSourceCodeRepo.checkSourceCodeValidity();
        }
        // Update bitbucket workflows if token exists
        boolean hasBitbucketToken = bitbucketToken != null && bitbucketToken.getContent() != null;
        boolean hasGitHubToken = githubToken != null && githubToken.getContent() != null;
        boolean hasGitLabToken = gitlabToken != null && gitlabToken.getContent() != null;
        if (!hasBitbucketToken && !hasGitHubToken && !hasGitLabToken) {
            throw new CustomWebApplicationException(
                "No source control repository token found.  Please link at least one source control repository token to your account.",
                HttpStatus.SC_BAD_REQUEST);
        }
        try {
            if (hasBitbucketToken) {
                // get workflows from bitbucket for a user and updates db
                refreshHelper(bitBucketSourceCodeRepo, user, organization, alreadyProcessed);
            }
            // Update github workflows if token exists
            if (hasGitHubToken) {
                // get workflows from github for a user and updates db
                refreshHelper(gitHubSourceCodeRepo, user, organization, alreadyProcessed);
            }
            // Update gitlab workflows if token exists
            if (hasGitLabToken) {
                // get workflows from gitlab for a user and updates db
                refreshHelper(gitLabSourceCodeRepo, user, organization, alreadyProcessed);
            }
            // when 3) no data is found for a workflow in the db, we may want to create a warning, note, or label
        } catch (WebApplicationException ex) {
            LOG.error(user.getUsername() + ": " + "Failed to refresh user {}", user.getId());
            throw ex;
        }
    }

    /**
     * Gets a mapping of all workflows from git host, and updates/adds as appropriate
     *
     * @param sourceCodeRepoInterface interface to read data from source control
     * @param user                    the user that made the request to refresh
     * @param organization            if specified, only refresh if workflow belongs to the organization
     */
    private void refreshHelper(final SourceCodeRepoInterface sourceCodeRepoInterface, User user, String organization, Set<Long> alreadyProcessed) {
        /** helpful code for testing, this was used to refresh a users existing workflows
         *  with a fixed github token for all users
         */
        boolean statsCollection = false;
        if (statsCollection) {
            List<Workflow> workflows = userDAO.findById(user.getId()).getEntries().stream().filter(entry -> entry instanceof Workflow).map(obj -> (Workflow)obj).collect(Collectors.toList());
            Map<String, String> workflowGitUrl2Name = new HashMap<>();
            for (Workflow workflow : workflows) {
                workflowGitUrl2Name.put(workflow.getGitUrl(), workflow.getOrganization() + "/" + workflow.getRepository());
            }
        }

        // Mapping of git url to repository name (owner/repo)
        final Map<String, String> workflowGitUrl2Name = sourceCodeRepoInterface.getWorkflowGitUrl2RepositoryId();
        LOG.info("found giturl to workflow name map" + Arrays.toString(workflowGitUrl2Name.entrySet().toArray()));
        if (organization != null) {
            workflowGitUrl2Name.entrySet().removeIf(thing -> !(thing.getValue().split("/"))[0].equals(organization));
        }
        // For each entry found of the associated git hosting service
        for (Map.Entry<String, String> entry : workflowGitUrl2Name.entrySet()) {
            LOG.info("refreshing " + entry.getKey());
            // Split entry into organization/namespace and repository/name
            String[] entryPathSplit = entry.getValue().split("/");
            sourceCodeRepoInterface.updateUsernameAndRepository(entryPathSplit[0], entryPathSplit[1]);

            // Get all workflows with the same giturl)
            final List<Workflow> byGitUrl = workflowDAO.findByGitUrl(entry.getKey());
            if (byGitUrl.size() > 0) {
                // Workflows exist with the given git url
                for (Workflow workflow : byGitUrl) {
                    // check whitelist for already processed workflows
                    if (alreadyProcessed.contains(workflow.getId())) {
                        continue;
                    }

                    // Update existing workflows with new information from the repository
                    // Note we pass the existing workflow as a base for the updated version of the workflow
                    final Workflow newWorkflow = sourceCodeRepoInterface.getWorkflow(entry.getValue(), Optional.of(workflow));

                    // Take ownership of these workflows
                    workflow.getUsers().add(user);

                    // Update the existing matching workflows based off of the new information
                    updateDBWorkflowWithSourceControlWorkflow(workflow, newWorkflow);
                    alreadyProcessed.add(workflow.getId());
                }
            } else {
                // Workflows are not registered for the given git url, add one
                final Workflow newWorkflow = sourceCodeRepoInterface.getWorkflow(entry.getValue(), Optional.empty());

                // The workflow was successfully created
                if (newWorkflow != null) {
                    final long workflowID = workflowDAO.create(newWorkflow);

                    // need to create nested data models
                    final Workflow workflowFromDB = workflowDAO.findById(workflowID);
                    workflowFromDB.getUsers().add(user);

                    // Update newly created template workflow (workflowFromDB) with found data from the repository
                    updateDBWorkflowWithSourceControlWorkflow(workflowFromDB, newWorkflow);
                    alreadyProcessed.add(workflowFromDB.getId());
                }
            }
        }
    }

    private List<Token> checkOnBitbucketToken(User user) {
        List<Token> tokens = tokenDAO.findBitbucketByUserId(user.getId());

        if (!tokens.isEmpty()) {
            Token bitbucketToken = tokens.get(0);
            refreshBitbucketToken(bitbucketToken, client, tokenDAO, bitbucketClientID, bitbucketClientSecret);
        }

        return tokenDAO.findByUserId(user.getId());
    }

    @GET
    @Path("/{workflowId}/refresh")
    @Timed
    @UnitOfWork
    @ApiOperation(value = "Refresh one particular workflow. Always do a full refresh when targeted", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Workflow.class)
    public Workflow refresh(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "workflow ID", required = true) @PathParam("workflowId") Long workflowId) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);
        checkUser(user, workflow);

        // get a live user for the following
        user = userDAO.findById(user.getId());
        // Update user data
        user.updateUserMetadata(tokenDAO);

        // Set up source code interface and ensure token is set up
        final SourceCodeRepoInterface sourceCodeRepo = getSourceCodeRepoInterface(workflow.getGitUrl(), user);

        // do a full refresh when targeted like this
        workflow.setMode(WorkflowMode.FULL);
        // look for checker workflows to associate with if applicable
        if (!workflow.isIsChecker() && workflow.getDescriptorType().equals(CWL_STRING) || workflow.getDescriptorType().equals(WDL_STRING)) {
            String workflowName = workflow.getWorkflowName() == null ? "" : workflow.getWorkflowName();
            String checkerWorkflowName = "/" + workflowName + (workflow.getDescriptorType().equals(CWL_STRING) ? CWL_CHECKER : WDL_CHECKER);
            Workflow byPath = workflowDAO.findByPath(workflow.getPath() + checkerWorkflowName, false);
            if (byPath != null && workflow.getCheckerWorkflow() == null) {
                workflow.setCheckerWorkflow(byPath);
            }
        }

        final Workflow newWorkflow = sourceCodeRepo.getWorkflow(workflow.getOrganization() + '/' + workflow.getRepository(), Optional.of(workflow));
        workflow.getUsers().add(user);
        updateDBWorkflowWithSourceControlWorkflow(workflow, newWorkflow);


        // Refresh checker workflow
        if (!workflow.isIsChecker() && workflow.getCheckerWorkflow() != null) {
            refresh(user, workflow.getCheckerWorkflow().getId());
        }

        elasticManager.handleIndexUpdate(newWorkflow, ElasticMode.UPDATE);
        return workflow;
    }

    /**
     * @param workflow    workflow to be updated
     * @param newWorkflow workflow to grab new content from
     */
    private void updateDBWorkflowWithSourceControlWorkflow(Workflow workflow, Workflow newWorkflow) {
        // update root workflow
        workflow.update(newWorkflow);
        // update workflow versions
        Map<String, WorkflowVersion> existingVersionMap = new HashMap<>();
        workflow.getWorkflowVersions().forEach(version -> existingVersionMap.put(version.getName(), version));

        // delete versions that exist in old workflow but do not exist in newWorkflow
        Map<String, WorkflowVersion> newVersionMap = new HashMap<>();
        newWorkflow.getWorkflowVersions().forEach(version -> newVersionMap.put(version.getName(), version));
        Sets.SetView<String> removedVersions = Sets.difference(existingVersionMap.keySet(), newVersionMap.keySet());
        for (String version : removedVersions) {
            workflow.removeWorkflowVersion(existingVersionMap.get(version));
        }

        // Then copy over content that changed
        for (WorkflowVersion version : newWorkflow.getVersions()) {
            WorkflowVersion workflowVersionFromDB = existingVersionMap.get(version.getName());
            if (existingVersionMap.containsKey(version.getName())) {
                workflowVersionFromDB.update(version);
            } else {
                // create a new one and replace the old one
                final long workflowVersionId = workflowVersionDAO.create(version);
                workflowVersionFromDB = workflowVersionDAO.findById(workflowVersionId);
                workflow.getVersions().add(workflowVersionFromDB);
                existingVersionMap.put(workflowVersionFromDB.getName(), workflowVersionFromDB);
            }

            // Update source files for each version
            Map<String, SourceFile> existingFileMap = new HashMap<>();
            workflowVersionFromDB.getSourceFiles().forEach(file -> existingFileMap.put(file.getType().toString() + file.getPath(), file));
            for (SourceFile file : version.getSourceFiles()) {
                if (existingFileMap.containsKey(file.getType().toString() + file.getPath())) {
                    existingFileMap.get(file.getType().toString() + file.getPath()).setContent(file.getContent());
                } else {
                    final long fileID = fileDAO.create(file);
                    final SourceFile fileFromDB = fileDAO.findById(fileID);
                    workflowVersionFromDB.getSourceFiles().add(fileFromDB);
                }
            }

            // Remove existing files that are no longer present
            for (Map.Entry<String, SourceFile> entry : existingFileMap.entrySet()) {
                boolean toDelete = true;
                for (SourceFile file : version.getSourceFiles()) {
                    if (entry.getKey().equals(file.getType().toString() + file.getPath())) {
                        toDelete = false;
                    }
                }
                if (toDelete) {
                    workflowVersionFromDB.getSourceFiles().remove(entry.getValue());
                }
            }
        }
    }

    @GET
    @Timed
    @UnitOfWork
    @RolesAllowed("admin")
    @ApiOperation(value = "List all workflows cached in database", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "List workflows currently known. Admin Only", response = Workflow.class, responseContainer = "List")
    public List<Workflow> allWorkflows(@ApiParam(hidden = true) @Auth User user) {
        return workflowDAO.findAll();
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/{workflowId}")
    @ApiOperation(value = "Get a registered workflow", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Workflow.class)
    public Workflow getWorkflow(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "workflow ID", required = true) @PathParam("workflowId") Long workflowId) {
        Workflow c = workflowDAO.findById(workflowId);
        checkEntry(c);

        checkUser(user, c);
        return c;
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/labels")
    @ApiOperation(value = "Update the labels linked to a workflow.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Labels are alphanumerical (case-insensitive and may contain internal hyphens), given in a comma-delimited list.", response = Workflow.class)
    public Workflow updateLabels(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "Tool to modify.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "Comma-delimited list of labels.", required = true) @QueryParam("labels") String labelStrings, @ApiParam(value = "This is here to appease Swagger. It requires PUT methods to have a body, even if it is empty. Please leave it empty.") String emptyBody) {
        Workflow c = workflowDAO.findById(workflowId);
        checkEntry(c);

        EntryLabelHelper<Workflow> labeller = new EntryLabelHelper<>(labelDAO);

        Workflow workflow = labeller.updateLabels(c, labelStrings);
        elasticManager.handleIndexUpdate(workflow, ElasticMode.UPDATE);
        return workflow;
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{workflowId}")
    @ApiOperation(value = "Update the workflow with the given workflow.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Workflow.class)
    public Workflow updateWorkflow(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "Workflow to modify.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "Workflow with updated information", required = true) Workflow workflow) {
        Workflow c = workflowDAO.findById(workflowId);
        checkEntry(c);

        checkUser(user, c);

        Workflow duplicate = workflowDAO.findByPath(workflow.getWorkflowPath(), false);

        if (duplicate != null && duplicate.getId() != workflowId) {
            LOG.info(user.getUsername() + ": " + "duplicate workflow found: {}" + workflow.getWorkflowPath());
            throw new CustomWebApplicationException("Workflow " + workflow.getWorkflowPath() + " already exists.", HttpStatus.SC_BAD_REQUEST);
        }

        updateInfo(c, workflow);
        Workflow result = workflowDAO.findById(workflowId);
        checkEntry(result);
        elasticManager.handleIndexUpdate(result, ElasticMode.UPDATE);
        return result;

    }

    // Used to update workflow manually (not refresh)
    private void updateInfo(Workflow oldWorkflow, Workflow newWorkflow) {
        // If workflow is FULL and descriptor type is being changed throw an error
        if (Objects.equals(oldWorkflow.getMode(), WorkflowMode.FULL) && !Objects.equals(oldWorkflow.getDescriptorType(), newWorkflow.getDescriptorType())) {
            throw new CustomWebApplicationException("You cannot change the descriptor type of a FULL workflow.", HttpStatus.SC_BAD_REQUEST);
        }

        // Only copy workflow type if old workflow is a STUB
        if (Objects.equals(oldWorkflow.getMode(), WorkflowMode.STUB)) {
            oldWorkflow.setDescriptorType(newWorkflow.getDescriptorType());
        }

        oldWorkflow.setDefaultWorkflowPath(newWorkflow.getDefaultWorkflowPath());
        oldWorkflow.setDefaultTestParameterFilePath(newWorkflow.getDefaultTestParameterFilePath());
        if (newWorkflow.getDefaultVersion() != null) {
            if (!oldWorkflow.checkAndSetDefaultVersion(newWorkflow.getDefaultVersion())) {
                throw new CustomWebApplicationException("Workflow version does not exist.", HttpStatus.SC_BAD_REQUEST);
            }
        }
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/verify/{workflowVersionId}")
    @RolesAllowed("admin")
    @ApiOperation(value = "Verify or unverify a workflow. ADMIN ONLY", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = WorkflowVersion.class, responseContainer = "List")
    public Set<WorkflowVersion> verifyWorkflowVersion(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "Workflow to modify.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "workflowVersionId", required = true) @PathParam("workflowVersionId") Long workflowVersionId, @ApiParam(value = "Object containing verification information.", required = true) VerifyRequest verifyRequest) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);
        // Note: if you set someone as an admin, they are not actually admin right away. Users must wait until after the
        // expireAfterAccess time in the authenticationCachePolicy expires (10m by default)
        checkUser(user, workflow);

        WorkflowVersion workflowVersion = workflowVersionDAO.findById(workflowVersionId);
        if (workflowVersion == null) {
            LOG.error(user.getUsername() + ": could not find version: " + workflow.getWorkflowPath());
            throw new CustomWebApplicationException("Version not found.", HttpStatus.SC_BAD_REQUEST);

        }

        if (verifyRequest.getVerify()) {
            if (Strings.isNullOrEmpty(verifyRequest.getVerifiedSource())) {
                throw new CustomWebApplicationException("A source must be included to verify a workflow.", HttpStatus.SC_BAD_REQUEST);
            }
            workflowVersion.updateVerified(true, verifyRequest.getVerifiedSource());
        } else {
            workflowVersion.updateVerified(false, null);
        }

        Workflow result = workflowDAO.findById(workflowId);
        checkEntry(result);
        elasticManager.handleIndexUpdate(result, ElasticMode.UPDATE);
        return result.getWorkflowVersions();
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/requestDOI/{workflowVersionId}")
    @ApiOperation(value = "Request a DOI for this version of a workflow", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = WorkflowVersion.class, responseContainer = "List")
    public Set<WorkflowVersion> requestDOIForWorkflowVersion(@ApiParam(hidden = true) @Auth User user,
        @ApiParam(value = "Workflow to modify.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "workflowVersionId", required = true) @PathParam("workflowVersionId") Long workflowVersionId) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);
        checkUser(user, workflow);

        WorkflowVersion workflowVersion = workflowVersionDAO.findById(workflowVersionId);
        if (workflowVersion == null) {
            LOG.error(user.getUsername() + ": could not find version: " + workflow.getPath());
            throw new CustomWebApplicationException("Version not found.", HttpStatus.SC_BAD_REQUEST);

        }

        if (workflowVersion.getDoiStatus() != Version.DOIStatus.CREATED) {
            DOIGeneratorInterface generator = DOIGeneratorFactory.createDOIGenerator();
            generator.createDOIForWorkflow(workflowId, workflowVersionId);
            workflowVersion.setDoiStatus(Version.DOIStatus.REQUESTED);
        }

        Workflow result = workflowDAO.findById(workflowId);
        checkEntry(result);
        elasticManager.handleIndexUpdate(result, ElasticMode.UPDATE);
        return result.getWorkflowVersions();

    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/resetVersionPaths")
    @ApiOperation(value = "Change the workflow paths", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Workflow version correspond to each row of the versions table listing all information for a workflow", response = Workflow.class)
    public Workflow updateWorkflowPath(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "Workflow to modify.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "Workflow with updated information", required = true) Workflow workflow) {

        Workflow c = workflowDAO.findById(workflowId);

        //check if the user and the entry is correct
        checkEntry(c);
        checkUser(user, c);

        //update the workflow path in all workflowVersions
        Set<WorkflowVersion> versions = c.getVersions();
        for (WorkflowVersion version : versions) {
            if (!version.isDirtyBit()) {
                version.setWorkflowPath(workflow.getDefaultWorkflowPath());
            }
        }
        elasticManager.handleIndexUpdate(c, ElasticMode.UPDATE);
        return c;
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/users")
    @ApiOperation(value = "Get users of a workflow", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = User.class, responseContainer = "List")
    public List<User> getUsers(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "workflow ID", required = true) @PathParam("workflowId") Long workflowId) {
        Workflow c = workflowDAO.findById(workflowId);
        checkEntry(c);

        checkUser(user, c);

        return new ArrayList<>(c.getUsers());
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/published/{workflowId}")
    @ApiOperation(value = "Get a published workflow", notes = "NO authentication", response = Workflow.class)
    public Workflow getPublishedWorkflow(@ApiParam(value = "Workflow ID", required = true) @PathParam("workflowId") Long workflowId) {
        Workflow workflow = workflowDAO.findPublishedById(workflowId);
        checkEntry(workflow);
        return filterContainersForHiddenTags(workflow);
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/organization/{organization}/published")
    @ApiOperation(value = "List all published workflows belonging to the specified namespace", notes = "NO authentication", response = Workflow.class, responseContainer = "List")
    public List<Workflow> getPublishedWorkflowsByOrganization(@ApiParam(value = "organization", required = true) @PathParam("organization") String organization) {
        List<Workflow> workflows = workflowDAO.findPublishedByOrganization(organization);
        filterContainersForHiddenTags(workflows);
        return workflows;
    }

    @POST
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/publish")
    @ApiOperation(value = "Publish or unpublish a workflow", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Publish/publish a workflow (public or private).", response = Workflow.class)
    public Workflow publish(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "Workflow id to publish/unpublish", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "PublishRequest to refresh the list of repos for a user", required = true) PublishRequest request) {
        Workflow c = workflowDAO.findById(workflowId);
        checkEntry(c);

        checkUser(user, c);

        Workflow checker = c.getCheckerWorkflow();

        if (request.getPublish()) {
            boolean validTag = false;
            Set<WorkflowVersion> versions = c.getVersions();
            for (WorkflowVersion workflowVersion : versions) {
                if (workflowVersion.isValid()) {
                    validTag = true;
                    break;
                }
            }

            if (validTag && !c.getGitUrl().isEmpty()) {
                c.setIsPublished(true);
                if (checker != null) {
                    checker.setIsPublished(true);
                }
            } else {
                throw new CustomWebApplicationException("Repository does not meet requirements to publish.", HttpStatus.SC_BAD_REQUEST);
            }
        } else {
            c.setIsPublished(false);
            if (checker != null) {
                checker.setIsPublished(false);
            }
        }

        long id = workflowDAO.create(c);
        c = workflowDAO.findById(id);
        if (request.getPublish()) {
            elasticManager.handleIndexUpdate(c, ElasticMode.UPDATE);
        } else {
            elasticManager.handleIndexUpdate(c, ElasticMode.DELETE);
        }
        return c;
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("published")
    @ApiOperation(value = "List all published workflows.", tags = { "workflows" }, notes = "NO authentication", response = Workflow.class, responseContainer = "List")
    public List<Workflow> allPublishedWorkflows() {
        List<Workflow> tools = workflowDAO.findAllPublished();
        filterContainersForHiddenTags(tools);
        return tools;
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/path/workflow/{repository}")
    @ApiOperation(value = "Get a workflow by path", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Lists info of workflow. Enter full path.", response = Workflow.class)
    public Workflow getWorkflowByPath(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "repository path", required = true) @PathParam("repository") String path) {

        Workflow workflow = workflowDAO.findByPath(path, false);
        checkEntry(workflow);
        checkUser(user, workflow);
        return workflow;
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/path/entry/{repository}")
    @ApiOperation(value = "Get an entry by path", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Gets an entry from the path. Enter full path.", response = Entry.class)
    public Entry getEntryByPath(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "repository path", required = true) @PathParam("repository") String path) {
        MutablePair<String, Entry> entryPair = toolDAO.findEntryByPath(path, false);

        // Check if the entry exists
        if (entryPair == null) {
            throw new CustomWebApplicationException("Entry not found", HttpStatus.SC_BAD_REQUEST);
        }

        // Ensure the user has access
        checkUser(user, entryPair.getValue());

        return entryPair.getValue();
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/path/entry/{repository}/published")
    @ApiOperation(value = "Get an entry by path", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Gets a published entry from the path. Enter full path.", response = Entry.class)
    public Entry getPublishedEntryByPath(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "repository path", required = true) @PathParam("repository") String path) {
        MutablePair<String, Entry> entryPair = toolDAO.findEntryByPath(path, true);

        // Check if the entry exists
        if (entryPair == null) {
            throw new CustomWebApplicationException("Entry not found", HttpStatus.SC_BAD_REQUEST);
        }

        return entryPair.getValue();
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/path/{repository}")
    @ApiOperation(value = "Get a list of workflows by path", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Lists info of workflow. Enter full path.", response = Workflow.class, responseContainer = "List")
    public List<Workflow> getAllWorkflowByPath(@ApiParam(hidden = true) @Auth User user,
        @ApiParam(value = "repository path", required = true) @PathParam("repository") String path) {
        List<Workflow> workflows = workflowDAO.findAllByPath(path, false);
        checkEntry(workflows);
        AuthenticatedResourceInterface.checkUser(user, workflows);
        return workflows;
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/path/workflow/{repository}/published")
    @ApiOperation(value = "Get a published workflow by path", notes = "Lists info of workflow. Enter full path.", response = Workflow.class)
    public Workflow getPublishedWorkflowByPath(@ApiParam(value = "repository path", required = true) @PathParam("repository") String path) {
        Workflow workflow = workflowDAO.findByPath(path, true);
        checkEntry(workflow);
        return workflow;
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/search")
    @ApiOperation(value = "Search for matching published workflows.", notes = "Search on the name (full path name) and description. NO authentication", response = Workflow.class, responseContainer = "List", tags = {
        "workflows" })
    public List<Workflow> search(@QueryParam("pattern") String word) {
        return workflowDAO.searchPattern(word);
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/versions")
    @ApiOperation(value = "List the versions for a published workflow", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = WorkflowVersion.class, responseContainer = "List", hidden = true)
    public List<WorkflowVersion> tags(@ApiParam(hidden = true) @Auth User user, @QueryParam("workflowId") long workflowId) {
        Workflow repository = workflowDAO.findById(workflowId);
        checkEntry(repository);

        checkUser(user, repository);

        return new ArrayList<>(repository.getVersions());
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/verifiedSources")
    @ApiOperation(value = "Get a semicolon delimited list of verified sources", tags = { "workflows" }, notes = "Does not need authentication", response = String.class)
    public String verifiedSources(@ApiParam(value = "Workflow id", required = true) @PathParam("workflowId") Long workflowId) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);

        Set<String> verifiedSourcesArray = new HashSet<>();
        workflow.getWorkflowVersions().stream().filter(Version::isVerified).forEach((WorkflowVersion v) -> verifiedSourcesArray.add(v.getVerifiedSource()));

        JSONArray jsonArray;
        try {
            jsonArray = new JSONArray(verifiedSourcesArray.toArray());
        } catch (JSONException ex) {
            throw new CustomWebApplicationException("There was an error converting the array of verified sources to a JSON array.",
                HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }

        return jsonArray.toString();
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/cwl")
    @ApiOperation(value = "Get the corresponding Dockstore.cwl file on Github.", tags = { "workflows" }, notes = "Does not need authentication", response = SourceFile.class)
    public SourceFile cwl(@ApiParam(value = "Workflow id", required = true) @PathParam("workflowId") Long workflowId, @QueryParam("tag") String tag) {
        return getSourceFile(workflowId, tag, FileType.DOCKSTORE_CWL);
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/wdl")
    @ApiOperation(value = "Get the corresponding Dockstore.wdl file on Github.", tags = { "workflows" }, notes = "Does not need authentication", response = SourceFile.class)
    public SourceFile wdl(@ApiParam(value = "Workflow id", required = true) @PathParam("workflowId") Long workflowId, @QueryParam("tag") String tag) {
        return getSourceFile(workflowId, tag, FileType.DOCKSTORE_WDL);
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/nextflow")
    @ApiOperation(value = "Get the corresponding nextflow.config file on Github.", tags = {
        "workflows" }, notes = "Does not need authentication", response = SourceFile.class)
    public SourceFile nextflow(@ApiParam(value = "Workflow id", required = true) @PathParam("workflowId") Long workflowId,
        @QueryParam("tag") String tag) {
        return getSourceFile(workflowId, tag, FileType.NEXTFLOW_CONFIG);
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/cwl/{relative-path}")
    @ApiOperation(value = "Get the corresponding Dockstore.cwl file on Github.", tags = { "workflows" }, notes = "Does not need authentication", response = SourceFile.class)
    public SourceFile secondaryCwlPath(@ApiParam(value = "Workflow id", required = true) @PathParam("workflowId") Long workflowId, @QueryParam("tag") String tag, @PathParam("relative-path") String path) {
        return getSourceFileByPath(workflowId, tag, FileType.DOCKSTORE_CWL, path);
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/wdl/{relative-path}")
    @ApiOperation(value = "Get the corresponding Dockstore.wdl file on Github.", tags = { "workflows" }, notes = "Does not need authentication", response = SourceFile.class)
    public SourceFile secondaryWdlPath(@ApiParam(value = "Workflow id", required = true) @PathParam("workflowId") Long workflowId, @QueryParam("tag") String tag, @PathParam("relative-path") String path) {
        return getSourceFileByPath(workflowId, tag, FileType.DOCKSTORE_WDL, path);
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/nextflow/{relative-path}")
    @ApiOperation(value = "Get the corresponding nextflow documents on Github.", tags = {
        "workflows" }, notes = "Does not need authentication", response = SourceFile.class)
    public SourceFile secondaryNextFlowPath(@ApiParam(value = "Workflow id", required = true) @PathParam("workflowId") Long workflowId,
        @QueryParam("tag") String tag, @PathParam("relative-path") String path) {

        return getSourceFileByPath(workflowId, tag, FileType.NEXTFLOW, path);
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/secondaryCwl")
    @ApiOperation(value = "Get the corresponding cwl documents on Github.", tags = { "workflows" }, notes = "Does not need authentication", response = SourceFile.class, responseContainer = "List")
    public List<SourceFile> secondaryCwl(@ApiParam(value = "Workflow id", required = true) @PathParam("workflowId") Long workflowId, @QueryParam("tag") String tag) {
        return getAllSecondaryFiles(workflowId, tag, FileType.DOCKSTORE_CWL);
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/secondaryWdl")
    @ApiOperation(value = "Get the corresponding wdl documents on Github.", tags = { "workflows" }, notes = "Does not need authentication", response = SourceFile.class, responseContainer = "List")
    public List<SourceFile> secondaryWdl(@ApiParam(value = "Workflow id", required = true) @PathParam("workflowId") Long workflowId, @QueryParam("tag") String tag) {
        return getAllSecondaryFiles(workflowId, tag, FileType.DOCKSTORE_WDL);
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/secondaryNextflow")
    @ApiOperation(value = "Get the corresponding Nextflow documents on Github.", tags = {
        "workflows" }, notes = "Does not need authentication", response = SourceFile.class, responseContainer = "List")
    public List<SourceFile> secondaryNextflow(@ApiParam(value = "Workflow id", required = true) @PathParam("workflowId") Long workflowId,
        @QueryParam("tag") String tag) {
        return getAllSecondaryFiles(workflowId, tag, FileType.NEXTFLOW);
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/testParameterFiles")
    @ApiOperation(value = "Get the corresponding test parameter files.", tags = { "workflows" }, notes = "Does not need authentication", response = SourceFile.class, responseContainer = "List")
    public List<SourceFile> getTestParameterFiles(@ApiParam(value = "Workflow id", required = true) @PathParam("workflowId") Long workflowId,
        @QueryParam("version") String version) {

        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);
        FileType testParameterType = workflow.getTestParameterType();
        return getAllSourceFiles(workflowId, version, testParameterType);
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/testParameterFiles")
    @ApiOperation(value = "Add test parameter files for a given version.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = SourceFile.class, responseContainer = "Set")
    public Set<SourceFile> addTestParameterFiles(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "Workflow to modify.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "List of paths.", required = true) @QueryParam("testParameterPaths") List<String> testParameterPaths, @ApiParam(value = "This is here to appease Swagger. It requires PUT methods to have a body, even if it is empty. Please leave it empty.") String emptyBody,
        @QueryParam("version") String version) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);

        if (workflow.getMode() == WorkflowMode.STUB) {
            String msg = "The workflow \'" + workflow.getWorkflowPath() + "\' is a STUB. Refresh the workflow if you want to add test parameter files";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }

        Optional<WorkflowVersion> potentialWorfklowVersion = workflow.getWorkflowVersions().stream()
            .filter((WorkflowVersion v) -> v.getName().equals(version)).findFirst();

        if (!potentialWorfklowVersion.isPresent()) {
            String msg = "The version \'" + version + "\' for workflow \'" + workflow.getWorkflowPath() + "\' does not exist.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }

        WorkflowVersion workflowVersion = potentialWorfklowVersion.get();

        if (!workflowVersion.isValid()) {
            String msg = "The version \'" + version + "\' for workflow \'" + workflow.getWorkflowPath() + "\' is invalid.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }

        Set<SourceFile> sourceFiles = workflowVersion.getSourceFiles();

        // Add new test parameter files
        FileType testParameterType = workflow.getTestParameterType();
        createTestParameters(testParameterPaths, workflowVersion, sourceFiles, testParameterType, fileDAO);
        elasticManager.handleIndexUpdate(workflow, ElasticMode.UPDATE);
        return workflowVersion.getSourceFiles();
    }

    @DELETE
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/testParameterFiles")
    @ApiOperation(value = "Delete test parameter files for a given version.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = SourceFile.class, responseContainer = "Set")
    public Set<SourceFile> deleteTestParameterFiles(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "Workflow to modify.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "List of paths.", required = true) @QueryParam("testParameterPaths") List<String> testParameterPaths, @QueryParam("version") String version) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);

        Optional<WorkflowVersion> potentialWorfklowVersion = workflow.getWorkflowVersions().stream()
            .filter((WorkflowVersion v) -> v.getName().equals(version)).findFirst();

        if (!potentialWorfklowVersion.isPresent()) {
            LOG.info("The version \'" + version + "\' for workflow \'" + workflow.getWorkflowPath() + "\' does not exist.");
            throw new CustomWebApplicationException("The version \'" + version + "\' for workflow \'" + workflow.getWorkflowPath() + "\' does not exist.",
                HttpStatus.SC_BAD_REQUEST);
        }

        WorkflowVersion workflowVersion = potentialWorfklowVersion.get();

        if (!workflowVersion.isValid()) {
            LOG.info("The version \'" + version + "\' for workflow \'" + workflow.getWorkflowPath() + "\' is invalid.");
            throw new CustomWebApplicationException("The version \'" + version + "\' for workflow \'" + workflow.getWorkflowPath() + "\' is invalid.",
                HttpStatus.SC_BAD_REQUEST);
        }

        Set<SourceFile> sourceFiles = workflowVersion.getSourceFiles();

        // Remove test parameter files
        FileType testParameterType = workflow.getTestParameterType();
        testParameterPaths.forEach(path -> sourceFiles.removeIf((SourceFile v) -> v.getPath().equals(path) && v.getType() == testParameterType));
        elasticManager.handleIndexUpdate(workflow, ElasticMode.UPDATE);
        return workflowVersion.getSourceFiles();
    }

    @POST
    @Timed
    @UnitOfWork
    @Path("/manualRegister")
    @SuppressWarnings("checkstyle:ParameterNumber")
    @ApiOperation(value = "Manually register a workflow", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Manually register workflow (public or private).", response = Workflow.class)
    public Workflow manualRegister(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "Workflow registry", required = true) @QueryParam("workflowRegistry") String workflowRegistry,
        @ApiParam(value = "Workflow repository", required = true) @QueryParam("workflowPath") String workflowPath, @ApiParam(value = "Workflow container new descriptor path (CWL or WDL) and/or name", required = true) @QueryParam("defaultWorkflowPath") String defaultWorkflowPath,
        @ApiParam(value = "Workflow name", required = true) @QueryParam("workflowName") String workflowName, @ApiParam(value = "Descriptor type", required = true) @QueryParam("descriptorType") String descriptorType,
        @ApiParam(value = "Default test parameter file path") @QueryParam("defaultTestParameterFilePath") String defaultTestParameterFilePath) {


        // Set up source code interface and ensure token is set up
        // construct git url like git@github.com:ga4gh/dockstore-ui.git
        String registryURLPrefix;
        SourceControl sourceControlEnum;
        if (workflowRegistry.toLowerCase().equals(SourceControl.BITBUCKET.getFriendlyName().toLowerCase())) {
            sourceControlEnum = SourceControl.BITBUCKET;
            registryURLPrefix = sourceControlEnum.toString();
        } else if (workflowRegistry.toLowerCase().equals(SourceControl.GITHUB.getFriendlyName().toLowerCase())) {
            sourceControlEnum = SourceControl.GITHUB;
            registryURLPrefix = sourceControlEnum.toString();
        } else if (workflowRegistry.toLowerCase().equals(SourceControl.GITLAB.getFriendlyName().toLowerCase())) {
            sourceControlEnum = SourceControl.GITLAB;
            registryURLPrefix = sourceControlEnum.toString();
        } else {
            throw new CustomWebApplicationException("The given git registry is not supported.", HttpStatus.SC_BAD_REQUEST);
        }
        String completeWorkflowPath = workflowPath;
        // Check that no duplicate workflow (same WorkflowPath) exists
        if (workflowName != null && !"".equals(workflowName)) {
            completeWorkflowPath += "/" + workflowName;
        }

        if (Strings.isNullOrEmpty(workflowName)) {
            workflowName = null;
        }

        if ("nextflow".equals(descriptorType) && !defaultWorkflowPath.endsWith("nextflow.config")) {
            throw new CustomWebApplicationException(
                "Please ensure that the given workflow path '" + defaultWorkflowPath + "' is of type " + descriptorType
                    + " and ends in the file nextflow.config", HttpStatus.SC_BAD_REQUEST);
        } else if (!"nextflow".equals(descriptorType) && !defaultWorkflowPath.endsWith(descriptorType)) {
            throw new CustomWebApplicationException(
                "Please ensure that the given workflow path '" + defaultWorkflowPath + "' is of type " + descriptorType + " and has the file extension " + descriptorType, HttpStatus.SC_BAD_REQUEST);
        }

        Workflow duplicate = workflowDAO.findByPath(sourceControlEnum.toString() + '/' + completeWorkflowPath, false);
        if (duplicate != null) {
            throw new CustomWebApplicationException("A workflow with the same path and name already exists.", HttpStatus.SC_BAD_REQUEST);
        }

        String gitURL = "git@" + registryURLPrefix + ":" + workflowPath + ".git";
        final SourceCodeRepoInterface sourceCodeRepo = getSourceCodeRepoInterface(gitURL, user);

        // Create workflow
        Workflow newWorkflow = sourceCodeRepo.getWorkflow(completeWorkflowPath, Optional.empty());

        if (newWorkflow == null) {
            throw new CustomWebApplicationException("Please enter a valid repository.", HttpStatus.SC_BAD_REQUEST);
        }
        newWorkflow.setDefaultWorkflowPath(defaultWorkflowPath);
        newWorkflow.setWorkflowName(workflowName);
        newWorkflow.setDescriptorType(descriptorType);
        newWorkflow.setDefaultTestParameterFilePath(defaultTestParameterFilePath);

        final long workflowID = workflowDAO.create(newWorkflow);
        // need to create nested data models
        final Workflow workflowFromDB = workflowDAO.findById(workflowID);
        workflowFromDB.getUsers().add(user);
        updateDBWorkflowWithSourceControlWorkflow(workflowFromDB, newWorkflow);
        return workflowDAO.findById(workflowID);

    }

    private SourceCodeRepoInterface getSourceCodeRepoInterface(String gitUrl, User user) {
        List<Token> tokens = checkOnBitbucketToken(user);
        Token bitbucketToken = Token.extractToken(tokens, TokenType.BITBUCKET_ORG.toString());
        Token githubToken = Token.extractToken(tokens, TokenType.GITHUB_COM.toString());
        Token gitlabToken = Token.extractToken(tokens, TokenType.GITLAB_COM.toString());

        final String bitbucketTokenContent = bitbucketToken == null ? null : bitbucketToken.getContent();
        final String gitHubTokenContent = githubToken == null ? null : githubToken.getContent();
        final String gitlabTokenContent = gitlabToken == null ? null : gitlabToken.getContent();

        final SourceCodeRepoInterface sourceCodeRepo = SourceCodeRepoFactory
            .createSourceCodeRepo(gitUrl, client, bitbucketTokenContent, gitlabTokenContent, gitHubTokenContent);
        if (sourceCodeRepo == null) {
            throw new CustomWebApplicationException("Git tokens invalid, please re-link your git accounts.", HttpStatus.SC_BAD_REQUEST);
        }
        return sourceCodeRepo;
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/workflowVersions")
    @ApiOperation(value = "Update the workflow versions linked to a workflow", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Workflow version correspond to each row of the versions table listing all information for a workflow", response = WorkflowVersion.class, responseContainer = "List")
    public Set<WorkflowVersion> updateWorkflowVersion(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "Workflow to modify.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "List of modified workflow versions", required = true) List<WorkflowVersion> workflowVersions) {

        Workflow w = workflowDAO.findById(workflowId);
        checkEntry(w);

        checkUser(user, w);

        // create a map for quick lookup
        Map<Long, WorkflowVersion> mapOfExistingWorkflowVersions = new HashMap<>();
        for (WorkflowVersion version : w.getVersions()) {
            mapOfExistingWorkflowVersions.put(version.getId(), version);
        }

        for (WorkflowVersion version : workflowVersions) {
            if (mapOfExistingWorkflowVersions.containsKey(version.getId())) {
                // remove existing copy and add the new one
                WorkflowVersion existingTag = mapOfExistingWorkflowVersions.get(version.getId());

                // If path changed then update dirty bit to true
                if (!existingTag.getWorkflowPath().equals(version.getWorkflowPath())) {
                    existingTag.setDirtyBit(true);
                }

                existingTag.updateByUser(version);
            }
        }
        Workflow result = workflowDAO.findById(workflowId);
        checkEntry(result);
        elasticManager.handleIndexUpdate(result, ElasticMode.UPDATE);
        return result.getVersions();
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/dag/{workflowVersionId}")
    @ApiOperation(value = "Get the DAG for a given workflow version", response = String.class)
    public String getWorkflowDag(@ApiParam(value = "workflowId", required = true) @PathParam("workflowId") Long workflowId, @ApiParam(value = "workflowVersionId", required = true) @PathParam("workflowVersionId") Long workflowVersionId) {
        Workflow workflow = workflowDAO.findById(workflowId);
        WorkflowVersion workflowVersion = getWorkflowVersion(workflow, workflowVersionId);
        SourceFile mainDescriptor = getMainDescriptorFile(workflowVersion);

        if (mainDescriptor != null) {
            File tmpDir = Files.createTempDir();
            Map<String, String> secondaryDescContent = extractDescriptorAndSecondaryFiles(workflowVersion, mainDescriptor, tmpDir);

            LanguageHandlerInterface lInterface = LanguageHandlerFactory.getInterface(workflow.getFileType());
            return lInterface.getContent(workflowVersion.getWorkflowPath(), mainDescriptor.getContent(), secondaryDescContent,
                LanguageHandlerInterface.Type.DAG, toolDAO);
        }
        return null;
    }

    /**
     * This method will create a json data consisting tool and its data required in a workflow for 'Tool' tab
     *
     * @param workflowId        workflow to grab tools for
     * @param workflowVersionId version of the workflow to grab tools for
     * @return json content consisting of a workflow and the tools it uses
     */
    @GET
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/tools/{workflowVersionId}")
    @ApiOperation(value = "Get the Tools for a given workflow version", response = String.class)
    public String getTableToolContent(@ApiParam(value = "workflowId", required = true) @PathParam("workflowId") Long workflowId, @ApiParam(value = "workflowVersionId", required = true) @PathParam("workflowVersionId") Long workflowVersionId) {

        Workflow workflow = workflowDAO.findById(workflowId);
        WorkflowVersion workflowVersion = getWorkflowVersion(workflow, workflowVersionId);
        if (workflowVersion == null) {
            throw new CustomWebApplicationException("workflow version " + workflowVersionId + " does not exist", HttpStatus.SC_BAD_REQUEST);
        }
        SourceFile mainDescriptor = getMainDescriptorFile(workflowVersion);
        if (mainDescriptor != null) {
            File tmpDir = Files.createTempDir();
            Map<String, String> secondaryDescContent = extractDescriptorAndSecondaryFiles(workflowVersion, mainDescriptor, tmpDir);
            LanguageHandlerInterface lInterface = LanguageHandlerFactory.getInterface(workflow.getFileType());
            return lInterface.getContent(workflowVersion.getWorkflowPath(), mainDescriptor.getContent(), secondaryDescContent,
                LanguageHandlerInterface.Type.TOOLS, toolDAO);
        }

        return null;
    }

    /**
     * Populates the return file with the descriptor and secondaryDescContent as a map between file paths and secondary files
     *
     * @param workflowVersion source control version to consider
     * @param mainDescriptor  database record for the main descriptor
     * @param tmpDir          a directory where to create the written out descriptor
     * @return secondary file map (string path -> string content)
     */
    private Map<String, String> extractDescriptorAndSecondaryFiles(WorkflowVersion workflowVersion, SourceFile mainDescriptor, File tmpDir) {
        Map<String, String> secondaryDescContent = new HashMap<>();
        // get secondary files
        for (SourceFile secondaryFile : workflowVersion.getSourceFiles()) {
            if (!secondaryFile.getPath().equals(workflowVersion.getWorkflowPath())) {
                secondaryDescContent.put(secondaryFile.getPath(), secondaryFile.getContent());
            }
        }
        return secondaryDescContent;
    }

    /**
     * This method will find the workflowVersion based on the workflowVersionId passed in the parameter and return it
     *
     * @param workflow          a workflow to grab a workflow version from
     * @param workflowVersionId the workflow version to get
     * @return WorkflowVersion
     */
    private WorkflowVersion getWorkflowVersion(Workflow workflow, Long workflowVersionId) {
        Set<WorkflowVersion> workflowVersions = workflow.getVersions();
        WorkflowVersion workflowVersion = null;

        for (WorkflowVersion wv : workflowVersions) {
            if (wv.getId() == workflowVersionId) {
                workflowVersion = wv;
                break;
            }
        }

        return workflowVersion;
    }

    /**
     * This method will find the main descriptor file based on the workflow version passed in the parameter
     *
     * @param workflowVersion workflowVersion with collects sourcefiles
     * @return mainDescriptor
     */
    private SourceFile getMainDescriptorFile(WorkflowVersion workflowVersion) {

        SourceFile mainDescriptor = null;
        for (SourceFile sourceFile : workflowVersion.getSourceFiles()) {
            if (sourceFile.getPath().equals(workflowVersion.getWorkflowPath())) {
                mainDescriptor = sourceFile;
                break;
            }
        }

        return mainDescriptor;
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/star")
    @ApiOperation(value = "Stars a workflow.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) })
    public void starEntry(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "Tool to star.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "StarRequest to star a repo for a user", required = true) StarRequest request) {
        Workflow workflow = workflowDAO.findById(workflowId);

        starEntryHelper(workflow, user, "workflow", workflow.getWorkflowPath());
        elasticManager.handleIndexUpdate(workflow, ElasticMode.UPDATE);
    }

    @DELETE
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/unstar")
    @ApiOperation(value = "Unstars a workflow.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) })
    public void unstarEntry(@ApiParam(hidden = true) @Auth User user, @ApiParam(value = "Workflow to unstar.", required = true) @PathParam("workflowId") Long workflowId) {
        Workflow workflow = workflowDAO.findById(workflowId);
        unstarEntryHelper(workflow, user, "workflow", workflow.getWorkflowPath());
        elasticManager.handleIndexUpdate(workflow, ElasticMode.UPDATE);
    }

    @GET
    @Path("/{workflowId}/starredUsers")
    @Timed
    @UnitOfWork
    @ApiOperation(value = "Returns list of users who starred the given Workflow", response = User.class, responseContainer = "List")
    public Set<User> getStarredUsers(@ApiParam(value = "Workflow to grab starred users for.", required = true) @PathParam("workflowId") Long workflowId) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);

        return workflow.getStarredUsers();
    }

    @POST
    @Timed
    @UnitOfWork
    @Path("/{entryId}/registerCheckerWorkflow/{descriptorType}")
    @ApiOperation(value = "Register a checker workflow and associates it with the given tool/workflow", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Entry.class)
    @SuppressWarnings("checkstyle:MagicNumber")
    public Entry registerCheckerWorkflow(@ApiParam(hidden = true) @Auth User user,
        @ApiParam(value = "Path of the main descriptor of the checker workflow (located in associated tool/workflow repository)", required = true) @QueryParam("checkerWorkflowPath") String checkerWorkflowPath,
        @ApiParam(value = "Default path to test parameter files for the checker workflow. If not specified will use that of the entry.") @QueryParam("testParameterPath") String testParameterPath,
        @ApiParam(value = "Entry Id of parent tool/workflow.", required = true) @PathParam("entryId") Long entryId,
        @ApiParam(value = "Descriptor type of the workflow, either cwl or wdl.", required = true, allowableValues = "cwl, wdl") @PathParam("descriptorType") String descriptorType) {
        // Find the entry
        MutablePair<String, Entry> entryPair = toolDAO.findEntryById(entryId);

        // Check if valid descriptor type
        if (!Objects.equals(descriptorType, DescriptorType.CWL.toString().toLowerCase()) && !Objects.equals(descriptorType, DescriptorType.WDL.toString().toLowerCase())) {
            throw new CustomWebApplicationException(descriptorType + " is not a valid descriptor type. Only cwl and wdl are valid.", HttpStatus.SC_BAD_REQUEST);
        }

        // Check if the entry exists
        if (entryPair.getValue() == null) {
            throw new CustomWebApplicationException("No entry with the given ID exists.", HttpStatus.SC_BAD_REQUEST);
        }

        // Don't allow workflow stubs
        if (Objects.equals(entryPair.getKey(), "workflow")) {
            Workflow workflow = (Workflow) entryPair.getValue();
            if (Objects.equals(workflow.getMode().name(), WorkflowMode.STUB.toString())) {
                throw new CustomWebApplicationException("Checker workflows cannot be added to workflow stubs.", HttpStatus.SC_BAD_REQUEST);
            }
        }

        // Ensure that the entry has no checker workflows already
        if (entryPair.getValue().getCheckerWorkflow() != null) {
            throw new CustomWebApplicationException("The given entry already has a checker workflow.", HttpStatus.SC_BAD_REQUEST);
        }

        // Checker workflow variables
        String defaultTestParameterPath;
        String organization;
        String repository;
        String sourceControl;
        boolean isPublished;
        String gitUrl;
        Date lastUpdated;
        String workflowName;

        // Grab information if tool
        if (Objects.equals(entryPair.getKey(), "tool")) {
            // Get tool
            Tool tool = (Tool)entryPair.getValue();

            // Generate workflow name
            workflowName = MoreObjects.firstNonNull(tool.getToolname(), "");

            // Get default test parameter path and toolname
            if (Objects.equals(descriptorType.toLowerCase(), DescriptorType.WDL.toString().toLowerCase())) {
                workflowName += "_wdl_checker";
                defaultTestParameterPath = tool.getDefaultTestWdlParameterFile();
            } else if (Objects.equals(descriptorType.toLowerCase(), DescriptorType.CWL.toString().toLowerCase())) {
                workflowName += "_cwl_checker";
                defaultTestParameterPath = tool.getDefaultTestCwlParameterFile();
            } else {
                throw new UnsupportedOperationException("The descriptor type " + descriptorType + " is not valid.\nSupported types include cwl and wdl.");
            }

            // Determine gitUrl
            gitUrl = tool.getGitUrl();

            // Determine source control, org, and repo
            Pattern p = Pattern.compile("git\\@(\\S+):(\\S+)/(\\S+)\\.git");
            Matcher m = p.matcher(tool.getGitUrl());
            if (m.find()) {
                sourceControl = m.group(1);
                organization = m.group(2);
                repository = m.group(3);
            } else {
                throw new CustomWebApplicationException("Problem parsing git url.", HttpStatus.SC_BAD_REQUEST);
            }

            // Determine publish information
            isPublished = tool.getIsPublished();

            // Determine last updated
            lastUpdated = tool.getLastUpdated();

        } else if (Objects.equals(entryPair.getKey(), "workflow")) {
            // Get workflow
            Workflow workflow = (Workflow)entryPair.getValue();

            // Copy over common attributes
            defaultTestParameterPath = workflow.getDefaultTestParameterFilePath();
            organization = workflow.getOrganization();
            repository = workflow.getRepository();
            sourceControl = workflow.getSourceControl();
            isPublished = workflow.getIsPublished();
            gitUrl = workflow.getGitUrl();
            lastUpdated = workflow.getLastUpdated();

            // Generate workflow name
            workflowName = MoreObjects.firstNonNull(workflow.getWorkflowName(), "");

            if (workflowName == null) {
                workflowName = "";
            }

            if (Objects.equals(workflow.getDescriptorType().toLowerCase(), DescriptorType.CWL.toString().toLowerCase())) {
                workflowName += CWL_CHECKER;
            } else if (Objects.equals(workflow.getDescriptorType().toLowerCase(), DescriptorType.WDL.toString().toLowerCase())) {
                workflowName +=  WDL_CHECKER;
            } else {
                throw new UnsupportedOperationException("The descriptor type " + workflow.getDescriptorType().toLowerCase() + " is not valid.\nSupported types include cwl and wdl.");
            }
        } else {
            throw new CustomWebApplicationException("No entry with the given ID exists.", HttpStatus.SC_BAD_REQUEST);
        }

        // Create checker workflow
        Workflow checkerWorkflow = new Workflow();
        checkerWorkflow.setMode(WorkflowMode.STUB);
        checkerWorkflow.setDefaultWorkflowPath(checkerWorkflowPath);
        checkerWorkflow.setDefaultTestParameterFilePath(defaultTestParameterPath);
        checkerWorkflow.setOrganization(organization);
        checkerWorkflow.setRepository(repository);
        checkerWorkflow.setSourceControl(sourceControl);
        checkerWorkflow.setIsPublished(isPublished);
        checkerWorkflow.setGitUrl(gitUrl);
        checkerWorkflow.setLastUpdated(lastUpdated);
        checkerWorkflow.setWorkflowName(workflowName);
        checkerWorkflow.setDescriptorType(descriptorType);
        checkerWorkflow.setIsChecker(true);

        // Deal with possible custom default test parameter file
        if (testParameterPath != null) {
            checkerWorkflow.setDefaultTestParameterFilePath(testParameterPath);
        } else {
            checkerWorkflow.setDefaultTestParameterFilePath(defaultTestParameterPath);
        }

        // Persist checker workflow
        long id = workflowDAO.create(checkerWorkflow);
        checkerWorkflow.addUser(user);
        checkerWorkflow = workflowDAO.findById(id);
        elasticManager.handleIndexUpdate(checkerWorkflow, ElasticMode.UPDATE);

        // Update original entry with checker id
        entryPair.getValue().setCheckerWorkflow(checkerWorkflow);

        // Return the original entry
        MutablePair<String, Entry> originalEntryPair = toolDAO.findEntryById(entryId);
        return originalEntryPair.getValue();

    }


    @Override
    public EntryDAO getDAO() {
        return this.workflowDAO;
    }
}
