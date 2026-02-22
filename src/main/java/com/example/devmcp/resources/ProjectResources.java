package com.example.devmcp.resources;

import com.example.devmcp.BuildLogHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MCP Resources — Primitive 2.
 *
 * Resources are server-side data sources the LLM can browse (list) and read.
 * The client sees a URI — the server resolves it to content.
 *
 * Two URI patterns demonstrated:
 *   Static  — project://pom.xml, project://readme, project://build-log
 *              No path variables; method takes no URI-mapped params
 *   Template — project://file/{name}
 *              {name} maps to the String parameter automatically
 *
 * Return type can be plain String (auto-wrapped) or ReadResourceResult for
 * full control over MIME type and multiple content chunks.
 */
@Component
public class ProjectResources {

    private static final Logger log = LoggerFactory.getLogger(ProjectResources.class);

    private final BuildLogHolder buildLogHolder;

    public ProjectResources(BuildLogHolder buildLogHolder) {
        this.buildLogHolder = buildLogHolder;
    }

    @McpResource(
        uri = "project://pom.xml",
        name = "Maven POM",
        description = "The project's pom.xml — shows dependencies, plugins, Spring AI version, and build config. " +
                      "Useful for understanding the project's tech stack."
    )
    public String getPomXml() {
        return readFileOrPlaceholder("pom.xml", "<!-- pom.xml not found in working directory -->");
    }

    @McpResource(
        uri = "project://readme",
        name = "README",
        description = "The project README.md — explains what this MCP server does, " +
                      "which MCP primitives it implements, and how to connect clients."
    )
    public String getReadme() {
        return readFileOrPlaceholder("README.md",
            "# Dev MCP Server\n\nREADME.md not found in working directory.");
    }

    @McpResource(
        uri = "project://build-log",
        name = "Build Log",
        description = "Output of the most recent runBuildTool invocation. " +
                      "Includes stdout/stderr and, on failure, an AI-generated error analysis " +
                      "from MCP sampling. Run runBuildTool first to populate this resource."
    )
    public String getBuildLog() {
        return buildLogHolder.getLastBuildLog();
    }

    @McpResource(
        uri = "project://file/{name}",
        name = "Project File",
        description = "Reads any file from the current working directory by relative name. " +
                      "Example: 'src/main/resources/application.properties' or 'README.md'."
    )
    public String getProjectFile(String name) {
        return readFileOrPlaceholder(name, "File not found: " + name);
    }

    private String readFileOrPlaceholder(String filename, String placeholder) {
        try {
            return Files.readString(Path.of(filename));
        } catch (Exception e) {
            log.debug("Resource read failed for '{}': {}", filename, e.getMessage());
            return placeholder;
        }
    }
}
