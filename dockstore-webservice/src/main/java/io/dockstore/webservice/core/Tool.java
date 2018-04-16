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

package io.dockstore.webservice.core;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.OrderBy;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dockstore.client.cli.nested.AbstractEntryClient;
import io.dockstore.common.Registry;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import org.hibernate.annotations.Check;

/**
 * This describes one tool in the dockstore, extending entry with fields necessary to describe bioinformatics tools.
 * <p>
 * Logically, this currently means one tuple of registry (either quay or docker hub), organization, image name, and toolname which can be
 * associated with CWL and Dockerfile documents.
 *
 * @author xliu
 * @author dyuen
 */
@ApiModel(value = "DockstoreTool", description =
        "This describes one entry in the dockstore. Logically, this currently means one tuple of registry (either quay or docker hub), organization, image name, and toolname which can be\n"
                + " * associated with CWL and Dockerfile documents")
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = { "registry", "namespace", "name", "toolname" }))
@NamedQueries({
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findByNameAndNamespaceAndRegistry", query = "SELECT c FROM Tool c WHERE c.name = :name AND c.namespace = :namespace AND c.registry = :registry"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findPublishedById", query = "SELECT c FROM Tool c WHERE c.id = :id AND c.isPublished = true"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findAllPublished", query = "SELECT c FROM Tool c WHERE c.isPublished = true ORDER BY size(c.starredUsers) DESC"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findAll", query = "SELECT c FROM Tool c"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findByMode", query = "SELECT c FROM Tool c WHERE c.mode = :mode"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findPublishedByNamespace", query = "SELECT c FROM Tool c WHERE lower(c.namespace) = lower(:namespace) AND c.isPublished = true ORDER BY gitUrl"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.searchPattern", query = "SELECT c FROM Tool c WHERE (CONCAT(c.registry, '/', c.namespace, '/', c.name, '/', c.toolname) LIKE :pattern) OR (CONCAT(c.registry, '/', c.namespace, '/', c.name) LIKE :pattern) OR (c.description LIKE :pattern)) AND c.isPublished = true"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findByPath", query = "SELECT c FROM Tool c WHERE c.registry = :registry AND c.namespace = :namespace AND c.name = :name"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findPublishedByPath", query = "SELECT c FROM Tool c WHERE c.registry = :registry AND c.namespace = :namespace AND c.name = :name AND c.isPublished = true"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findByToolPath", query = "SELECT c FROM Tool c WHERE c.registry = :registry AND c.namespace = :namespace AND c.name = :name AND c.toolname = :toolname"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findPublishedByToolPath", query = "SELECT c FROM Tool c WHERE c.registry = :registry AND c.namespace = :namespace AND c.name = :name AND c.toolname = :toolname AND c.isPublished = true"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findByToolPathNullToolName", query = "SELECT c FROM Tool c WHERE c.registry = :registry AND c.namespace = :namespace AND c.name = :name AND c.toolname IS NULL"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findPublishedByToolPathNullToolName", query = "SELECT c FROM Tool c WHERE c.registry = :registry AND c.namespace = :namespace AND c.name = :name AND c.toolname IS NULL AND c.isPublished = true") })
// @formatter:off
@Check(constraints = "(defaultwdlpath is not null or defaultcwlpath is not null) "
    + "and (toolname NOT LIKE '\\_%')")
// @formatter:on
@SuppressWarnings("checkstyle:magicnumber")
public class Tool extends Entry<Tool, Tag> {

    @Column(nullable = false, columnDefinition = "Text default 'AUTO_DETECT_QUAY_TAGS_AUTOMATED_BUILDS'")
    @Enumerated(EnumType.STRING)
    @ApiModelProperty(value = "This indicates what mode this is in which informs how we do things like refresh, dockstore specific", required = true, position = 13)
    private ToolMode mode = ToolMode.AUTO_DETECT_QUAY_TAGS_AUTOMATED_BUILDS;

    @Column(nullable = false)
    @ApiModelProperty(value = "This is the name of the container, required: GA4GH", required = true, position = 14)
    private String name;

    @Column(columnDefinition = "text", nullable = false)
    @JsonProperty("default_dockerfile_path")
    @ApiModelProperty(value = "This indicates for the associated git repository, the default path to the Dockerfile, required: GA4GH", required = true, position = 15)
    private String defaultDockerfilePath = "/Dockerfile";

    // Add for new descriptor types
    @Column(columnDefinition = "text")
    @JsonProperty("default_cwl_path")
    @ApiModelProperty(value = "This indicates for the associated git repository, the default path to the CWL document, required: GA4GH", required = true, position = 16)
    private String defaultCwlPath = "/Dockstore.cwl";

    @Column(columnDefinition = "text default '/Dockstore.wdl'")
    @JsonProperty("default_wdl_path")
    @ApiModelProperty(value = "This indicates for the associated git repository, the default path to the WDL document", required = true, position = 17)
    private String defaultWdlPath = "/Dockstore.wdl";

    @Column(columnDefinition = "text")
    @JsonProperty("defaultCWLTestParameterFile")
    @ApiModelProperty(value = "This indicates for the associated git repository, the default path to the CWL test parameter file", required = true, position = 18)
    private String defaultTestCwlParameterFile = "/test.json";

    @Column(columnDefinition = "text")
    @JsonProperty("defaultWDLTestParameterFile")
    @ApiModelProperty(value = "This indicates for the associated git repository, the default path to the WDL test parameter file", required = true, position = 19)
    private String defaultTestWdlParameterFile = "/test.json";

    @Column
    @JsonProperty("tool_maintainer_email")
    @ApiModelProperty(value = "The email address of the tool maintainer. Required for private repositories", position = 20)
    private String toolMaintainerEmail = "";

    @Column(columnDefinition = "boolean default false")
    @JsonProperty("private_access")
    @ApiModelProperty(value = "Is the docker image private or not.", required = true, position = 21)
    private boolean privateAccess = false;

    @Column(columnDefinition = "Text")
    @ApiModelProperty(value = "This is the tool name of the container, when not-present this will function just like 0.1 dockstore"
            + "when present, this can be used to distinguish between two containers based on the same image, but associated with different "
            + "CWL and Dockerfile documents. i.e. two containers with the same registry+namespace+name but different toolnames "
            + "will be two different entries in the dockstore registry/namespace/name/tool, different options to edit tags, and "
            + "only the same insofar as they would \"docker pull\" the same image, required: GA4GH", position = 22)
    private String toolname;

    @Column
    @ApiModelProperty(value = "This is a docker namespace for the container, required: GA4GH", required = true, position = 23)
    private String namespace;

    @Column(nullable = false)
    @ApiModelProperty(value = "This is a specific docker provider like quay.io or dockerhub or n/a?, required: GA4GH", required = true, position = 24)
    private String registry;

    @Column
    @ApiModelProperty(value = "Implementation specific timestamp for last built", position = 25)
    private Date lastBuild;

    @OneToMany(fetch = FetchType.EAGER, orphanRemoval = true)
    @JoinTable(name = "tool_tag", joinColumns = @JoinColumn(name = "toolid", referencedColumnName = "id"), inverseJoinColumns = @JoinColumn(name = "tagid", referencedColumnName = "id"))
    @ApiModelProperty(value = "Implementation specific tracking of valid build tags for the docker container", position = 26)
    @OrderBy("id")
    private final SortedSet<Tag> tags;

    public Tool() {
        tags = new TreeSet<>();
    }

    public Tool(long id, String name) {
        super(id);
        // this.userId = userId;
        this.name = name;
        tags = new TreeSet<>();
    }

    @Override
    public Set<Tag> getVersions() {
        return tags;
    }

    /**
     * Used during refresh to update tools
     *
     * @param tool
     */
    public void update(Tool tool) {
        super.update(tool);
        this.setDescription(tool.getDescription());
        lastBuild = tool.getLastBuild();
        this.toolMaintainerEmail = tool.getToolMaintainerEmail();
        this.privateAccess = tool.isPrivateAccess();
    }

    @JsonProperty
    public String getName() {
        return name;
    }

    /**
     * @param name the repo name to set
     */
    public void setName(String name) {
        this.name = name;
    }

    @JsonProperty
    public String getNamespace() {
        return namespace;
    }

    /**
     * @param namespace the repo name to set
     */
    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    @JsonProperty("registry_string")
    public String getRegistry() {
        return registry;
    }

    public void setRegistry(String registry) {
        this.registry = registry;
    }

    @ApiModelProperty(position = 27)
    public String getPath() {
        String repositoryPath = registry + '/' + namespace + '/' + name;
        return repositoryPath;
    }

    /**
     * Calculated property for demonstrating search by language, inefficient
     * @return the languages that this tool supports
     */
    @JsonProperty
    @ApiModelProperty(position = 28)
    public List<String> getDescriptorType() {
        Set<SourceFile.FileType> set = this.getTags().stream().flatMap(tag -> tag.getSourceFiles().stream()).map(SourceFile::getType)
            .distinct().collect(Collectors.toSet());
        boolean supportsCWL = set.contains(SourceFile.FileType.DOCKSTORE_CWL);
        boolean supportsWDL = set.contains(SourceFile.FileType.DOCKSTORE_WDL);
        List<String> languages = new ArrayList<>();
        if (supportsCWL) {
            languages.add(AbstractEntryClient.Type.CWL.toString());
        }
        if (supportsWDL) {
            languages.add(AbstractEntryClient.Type.WDL.toString());
        }
        return languages;
    }

    @JsonProperty
    public Date getLastBuild() {
        return lastBuild;
    }

    public void setLastBuild(Date lastBuild) {
        this.lastBuild = lastBuild;
    }

    public Set<Tag> getTags() {
        return tags;
    }

    public void addTag(Tag tag) {
        tags.add(tag);
    }

    public boolean removeTag(Tag tag) {
        return tags.remove(tag);
    }

    @JsonProperty
    public ToolMode getMode() {
        return mode;
    }

    public void setMode(ToolMode mode) {
        this.mode = mode;
    }

    @JsonProperty
    public String getDefaultDockerfilePath() {
        return defaultDockerfilePath;
    }

    public void setDefaultDockerfilePath(String defaultDockerfilePath) {
        this.defaultDockerfilePath = defaultDockerfilePath;
    }

    // Add for new descriptor types
    @JsonProperty
    public String getDefaultCwlPath() {
        return defaultCwlPath;
    }

    public void setDefaultCwlPath(String defaultCwlPath) {
        this.defaultCwlPath = defaultCwlPath;
    }

    @JsonProperty
    public String getDefaultWdlPath() {
        return defaultWdlPath;
    }

    public void setDefaultWdlPath(String defaultWdlPath) {
        this.defaultWdlPath = defaultWdlPath;
    }

    @JsonProperty
    public String getToolname() {
        return toolname;
    }

    public void setToolname(String toolname) {
        this.toolname = toolname;
    }

    @JsonProperty("tool_path")
    @ApiModelProperty(position = 29)
    public String getToolPath() {
        return getPath() + (toolname == null || toolname.isEmpty() ? "" : '/' + toolname);
    }

    /**
     * Change name of JsonProperty back to "registry_provider" once users no longer use the older client (CommonTestUtilities.OLD_DOCKSTORE_VERSION)
     * @return the registry as an enum
     */
    @Enumerated(EnumType.STRING)
    @JsonProperty("registry")
    @ApiModelProperty(position = 30)
    public Registry getRegistryProvider() {
        for (Registry r : Registry.values()) {
            if (r.toString() != null && r.toString().equals(this.registry)) {
                return r;
            }
        }

        // Deal with Amazon ECR
        if (this.registry != null && this.registry.matches("^[a-zA-Z0-9]+\\.dkr\\.ecr\\.[a-zA-Z0-9]+\\.amazonaws\\.com")) {
            return Registry.AMAZON_ECR;
        } else {
            return null;
        }
    }

    /**
     * Remove this once users no longer use the old client (1.3.6)
     * @param registryThing
     */
    public void setRegistryProvider(Registry registryThing) {
        switch (registryThing) {
        case GITLAB:
            this.setRegistry("registry.gitlab.com");
            break;
        case QUAY_IO:
            this.setRegistry("quay.io");
            break;
        case AMAZON_ECR:
            break;
        case DOCKER_HUB:
            this.setRegistry("registry.hub.docker.com");
            break;
        default:
            break;
        }

    }

    /**
     * Remove this once users no longer use the old client (1.3.6)
     * @param newCustomDockerRegistryString
     */
    public void setCustomerDockerRegistryPath(String newCustomDockerRegistryString) {
        if (newCustomDockerRegistryString != null) {
            this.setRegistry(newCustomDockerRegistryString);
        }
    }

    /**
     * Remove this once users no longer use the old client (1.3.6)
     * @return
     */
    @JsonProperty("custom_docker_registry_path")
    public String getCustomDockerRegistryPath() {
        return this.registry;
    }

    public String getToolMaintainerEmail() {
        return toolMaintainerEmail;
    }

    public void setToolMaintainerEmail(String toolMaintainerEmail) {
        this.toolMaintainerEmail = toolMaintainerEmail;
    }

    public boolean isPrivateAccess() {
        return privateAccess;
    }

    public void setPrivateAccess(boolean privateAccess) {
        this.privateAccess = privateAccess;
    }

    public String getDefaultTestWdlParameterFile() {
        return defaultTestWdlParameterFile;
    }

    public void setDefaultTestWdlParameterFile(String defaultTestWdlParameterFile) {
        this.defaultTestWdlParameterFile = defaultTestWdlParameterFile;
    }

    public String getDefaultTestCwlParameterFile() {
        return defaultTestCwlParameterFile;
    }

    public void setDefaultTestCwlParameterFile(String defaultTestCwlParameterFile) {
        this.defaultTestCwlParameterFile = defaultTestCwlParameterFile;
    }

}
