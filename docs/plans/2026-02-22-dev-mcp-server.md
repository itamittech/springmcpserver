# Developer MCP Server Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a Maven-based Spring Boot MCP server demonstrating all 5 MCP primitives (Tools, Resources, Prompts, Sampling, Progress Notifications) plus Completions, using exclusively annotation-based Spring AI APIs around a developer productivity theme.

**Architecture:** Single `@SpringBootApplication` with `spring-ai-starter-mcp-server-webmvc` on Streamable HTTP port 8080. All MCP primitives declared via annotations (`@McpTool`, `@McpResource`, `@McpPrompt`, `@McpComplete`) on `@Component` beans — no programmatic `SyncXxxRegistration` beans needed. Progress and sampling use `McpSyncServerExchange` injected directly into annotated methods. A shared `BuildLogHolder` component bridges build output to the `project://build-log` resource.

**Tech Stack:** Spring Boot 4.0.3, Spring AI 2.0.0-M2, `spring-ai-starter-mcp-server-webmvc`, Maven, Java 25, JUnit 5, Mockito.

---

## Final Project Layout

```
week19-dev-mcp-server/
├── pom.xml
├── README.md
├── docs/plans/
│   ├── 2026-02-22-dev-mcp-server-design.md
│   └── 2026-02-22-dev-mcp-server.md          ← this file
└── src/
    ├── main/
    │   ├── java/com/example/devmcp/
    │   │   ├── DevMcpApplication.java          ← @SpringBootApplication
    │   │   ├── BuildLogHolder.java             ← @Component shared state
    │   │   ├── tools/
    │   │   │   └── BuildTools.java             ← @McpTool ×2 + progress + sampling
    │   │   ├── resources/
    │   │   │   └── ProjectResources.java       ← @McpResource ×4 (3 static + 1 template)
    │   │   └── prompts/
    │   │       └── DevPrompts.java             ← @McpPrompt ×3 + @McpComplete ×1
    │   └── resources/
    │       └── application.properties
    └── test/
        └── java/com/example/devmcp/
            ├── DevMcpApplicationTests.java
            ├── BuildLogHolderTest.java
            ├── tools/BuildToolsTest.java
            ├── resources/ProjectResourcesTest.java
            └── prompts/DevPromptsTest.java
```

---

## Spring AI MCP Annotation Reference

All MCP annotations live in `org.springaicommunity.mcp.annotation.*`

| Annotation | Purpose | Return types accepted |
|------------|---------|----------------------|
| `@McpTool` | Expose a callable tool | Any (String, record, void, etc.) |
| `@McpToolParam` | Describe a tool parameter | — (parameter annotation) |
| `@McpResource` | Expose a readable resource via URI | `String`, `ReadResourceResult` |
| `@McpPrompt` | Expose a reusable prompt template | `String`, `GetPromptResult` |
| `@McpArg` | Describe a prompt argument | — (parameter annotation) |
| `@McpComplete` | Provide auto-completion for a prompt arg | `List<String>`, `CompleteResult` |
| `@McpProgressToken` | Inject progress token from request | — (parameter annotation on `String`) |

Special injected parameters (auto-excluded from JSON schema):

| Type | Purpose |
|------|---------|
| `McpSyncServerExchange` | Full server context: logging, progress, sampling, capabilities |
| `McpTransportContext` | Lightweight stateless context |
| `McpMeta` | Request metadata map |

---

## Correct Imports (Spring AI 2.0.0-M2)

```java
// Annotations
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springaicommunity.mcp.annotation.McpProgressToken;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpComplete;

// Exchange — inject into any @McpTool/@McpResource/@McpPrompt for advanced ops
import org.springframework.ai.mcp.McpSyncServerExchange;

// Protocol types from the MCP Java SDK
import io.modelcontextprotocol.sdk.McpSchema;
import io.modelcontextprotocol.sdk.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.sdk.McpSchema.LoggingLevel;
import io.modelcontextprotocol.sdk.McpSchema.ProgressNotification;
import io.modelcontextprotocol.sdk.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.sdk.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.sdk.McpSchema.SamplingMessage;
import io.modelcontextprotocol.sdk.McpSchema.ModelPreferences;
import io.modelcontextprotocol.sdk.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.sdk.McpSchema.TextResourceContents;
import io.modelcontextprotocol.sdk.McpSchema.GetPromptResult;
import io.modelcontextprotocol.sdk.McpSchema.PromptMessage;
import io.modelcontextprotocol.sdk.McpSchema.Role;
import io.modelcontextprotocol.sdk.McpSchema.TextContent;
```

> **Note on imports:** If exact package paths differ in your version, the IDE will suggest the correct ones. The types themselves (`McpSyncServerExchange`, `LoggingMessageNotification`, etc.) are stable — only the packages may vary between milestone builds.

---

## Task 1: Maven Project Scaffold

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/example/devmcp/DevMcpApplication.java`
- Create: `src/main/resources/application.properties`
- Create: `src/test/java/com/example/devmcp/DevMcpApplicationTests.java`

**Step 1: Create directory tree**

```bash
cd week19-dev-mcp-server
mkdir -p src/main/java/com/example/devmcp/tools
mkdir -p src/main/java/com/example/devmcp/resources
mkdir -p src/main/java/com/example/devmcp/prompts
mkdir -p src/main/resources
mkdir -p src/test/java/com/example/devmcp/tools
mkdir -p src/test/java/com/example/devmcp/resources
mkdir -p src/test/java/com/example/devmcp/prompts
```

**Step 2: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.3</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>week19-dev-mcp-server</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>week19-dev-mcp-server</name>
    <description>Developer Productivity MCP Server — all 5 MCP primitives + completions</description>

    <properties>
        <java.version>25</java.version>
        <spring-ai.version>2.0.0-M2</spring-ai.version>
    </properties>

    <dependencies>
        <!-- MCP Server (Streamable HTTP via WebMVC) -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
        </dependency>

        <!-- .env file support -->
        <dependency>
            <groupId>me.paulschwarz</groupId>
            <artifactId>spring-dotenv</artifactId>
            <version>4.0.0</version>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

    <!-- Required for Spring AI 2.0.0-M2 (milestone) and Spring Boot 4.x -->
    <repositories>
        <repository>
            <id>spring-milestones</id>
            <name>Spring Milestones</name>
            <url>https://repo.spring.io/milestone</url>
            <snapshots><enabled>false</enabled></snapshots>
        </repository>
        <repository>
            <id>spring-snapshots</id>
            <name>Spring Snapshots</name>
            <url>https://repo.spring.io/snapshot</url>
            <releases><enabled>false</enabled></releases>
        </repository>
    </repositories>

    <pluginRepositories>
        <pluginRepository>
            <id>spring-milestones</id>
            <name>Spring Milestones</name>
            <url>https://repo.spring.io/milestone</url>
            <snapshots><enabled>false</enabled></snapshots>
        </pluginRepository>
    </pluginRepositories>
</project>
```

**Step 3: Create `DevMcpApplication.java`**

```java
package com.example.devmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DevMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(DevMcpApplication.class, args);
    }
}
```

**Step 4: Create `application.properties`**

```properties
# ── Transport ──────────────────────────────────────────────────────────────────
# Streamable HTTP: clients POST to http://localhost:8080/mcp
# SSE streaming is optional per-response (server decides)
spring.ai.mcp.server.protocol=STREAMABLE
server.port=8080

# ── Server type ────────────────────────────────────────────────────────────────
# SYNC: registers only non-reactive (@McpTool methods returning plain types)
# ASYNC: registers only reactive (Mono/Flux returns) — use for WebFlux
spring.ai.mcp.server.type=SYNC

# ── Identity ───────────────────────────────────────────────────────────────────
spring.ai.mcp.server.name=dev-mcp-server
spring.ai.mcp.server.version=1.0.0

# ── Annotation scanner ─────────────────────────────────────────────────────────
# Scans all @Component beans in this package for @McpTool/@McpResource/@McpPrompt
spring.ai.mcp.server.annotation-scanner.enabled=true
spring.ai.mcp.server.annotation-scanner.packages=com.example.devmcp

# ── Logging ────────────────────────────────────────────────────────────────────
logging.level.com.example.devmcp=DEBUG
```

**Step 5: Create `DevMcpApplicationTests.java`**

```java
package com.example.devmcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DevMcpApplicationTests {

    @Test
    void contextLoads() {
        // Verifies Spring context starts, all @McpTool/@McpResource/@McpPrompt
        // beans are discovered and registered without errors.
    }
}
```

**Step 6: Verify the context loads**

```bash
mvn test -Dtest=DevMcpApplicationTests
```

Expected:
```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Step 7: Commit**

```bash
git init
git add pom.xml src/ docs/
git commit -m "feat: scaffold Maven MCP server project with application.properties"
```

---

## Task 2: BuildLogHolder — Shared State

A thread-safe `@Component` that holds the last build output. Written by `BuildTools`, read by `ProjectResources` for the `project://build-log` resource.

**Files:**
- Create: `src/test/java/com/example/devmcp/BuildLogHolderTest.java`
- Create: `src/main/java/com/example/devmcp/BuildLogHolder.java`

**Step 1: Write the failing test**

```java
package com.example.devmcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildLogHolderTest {

    @Test
    void defaultMessage_isHelpful() {
        assertThat(new BuildLogHolder().getLastBuildLog())
            .isEqualTo("No build has been run yet.");
    }

    @Test
    void setAndGet_roundTrips() {
        BuildLogHolder holder = new BuildLogHolder();
        holder.setLastBuildLog("BUILD SUCCESS");
        assertThat(holder.getLastBuildLog()).isEqualTo("BUILD SUCCESS");
    }
}
```

**Step 2: Run to verify fail**

```bash
mvn test -Dtest=BuildLogHolderTest
```

Expected: FAIL — `BuildLogHolder` not found.

**Step 3: Create `BuildLogHolder.java`**

```java
package com.example.devmcp;

import org.springframework.stereotype.Component;

/**
 * Shared in-memory store for the most recent build output.
 *
 * Written by: BuildTools.runBuildTool (after each build)
 * Read by:    ProjectResources.getBuildLog (@McpResource project://build-log)
 *
 * volatile ensures visibility across threads without synchronization overhead
 * (one writer, many readers pattern).
 */
@Component
public class BuildLogHolder {

    private volatile String lastBuildLog = "No build has been run yet.";

    public String getLastBuildLog() {
        return lastBuildLog;
    }

    public void setLastBuildLog(String log) {
        this.lastBuildLog = log;
    }
}
```

**Step 4: Run to verify pass**

```bash
mvn test -Dtest=BuildLogHolderTest
```

Expected:
```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

**Step 5: Commit**

```bash
git add src/
git commit -m "feat: add BuildLogHolder shared state component"
```

---

## Task 3: BuildTools — Tools + Progress + Sampling (Primitives 1, 4, 5)

Two `@McpTool` methods in one `@Component`. The `runBuildTool` receives a `McpSyncServerExchange` (auto-injected, excluded from schema) and `@McpProgressToken` (auto-injected) to emit progress events and trigger sampling on failure.

**Files:**
- Create: `src/test/java/com/example/devmcp/tools/BuildToolsTest.java`
- Create: `src/main/java/com/example/devmcp/tools/BuildTools.java`

**Step 1: Write the failing test**

```java
package com.example.devmcp.tools;

import com.example.devmcp.BuildLogHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.McpSyncServerExchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest
class BuildToolsTest {

    @Autowired
    BuildTools buildTools;

    @Autowired
    BuildLogHolder buildLogHolder;

    // ── readFileTool ──────────────────────────────────────────────────────────

    @Test
    void readFileTool_returnsContent(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("hello.txt");
        Files.writeString(f, "Hello MCP");

        assertThat(buildTools.readFileTool(f.toString())).isEqualTo("Hello MCP");
    }

    @Test
    void readFileTool_returnsError_whenMissing() {
        assertThat(buildTools.readFileTool("/no/such/file.txt"))
            .startsWith("Error reading file:");
    }

    // ── runBuildTool ──────────────────────────────────────────────────────────

    @Test
    void runBuildTool_capturesOutput_andUpdatesHolder(@TempDir Path dir) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);

        // "echo hello" works cross-platform via ProcessBuilder with shell
        String output = buildTools.runBuildTool(exchange, null, "echo hello", dir.toString());

        assertThat(output).contains("hello");
        assertThat(buildLogHolder.getLastBuildLog()).contains("hello");
    }
}
```

**Step 2: Run to verify fail**

```bash
mvn test -Dtest=BuildToolsTest
```

Expected: FAIL — `BuildTools` not found.

**Step 3: Create `BuildTools.java`**

```java
package com.example.devmcp.tools;

import com.example.devmcp.BuildLogHolder;
import io.modelcontextprotocol.sdk.McpSchema;
import io.modelcontextprotocol.sdk.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.sdk.McpSchema.LoggingLevel;
import io.modelcontextprotocol.sdk.McpSchema.ProgressNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpProgressToken;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.McpSyncServerExchange;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP Tools — Primitive 1.
 *
 * Demonstrates two tool registration patterns:
 *  - readFileTool: simple @McpTool, no special params
 *  - runBuildTool: @McpTool with McpSyncServerExchange (progress + sampling)
 *
 * McpSyncServerExchange and @McpProgressToken are auto-injected by the
 * annotation processor and excluded from the JSON schema shown to clients.
 */
@Component
public class BuildTools {

    private static final Logger log = LoggerFactory.getLogger(BuildTools.class);

    private final BuildLogHolder buildLogHolder;

    public BuildTools(BuildLogHolder buildLogHolder) {
        this.buildLogHolder = buildLogHolder;
    }

    // ── Primitive 1: Tool (simple) ────────────────────────────────────────────

    @McpTool(
        name = "readFileTool",
        description = "Reads any text file from the filesystem and returns its content. " +
                      "Provide the absolute file path. Useful for inspecting source files, " +
                      "config files, build scripts, or logs."
    )
    public String readFileTool(
            @McpToolParam(description = "Absolute path to the file to read", required = true)
            String filePath) {
        try {
            return Files.readString(Path.of(filePath));
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    // ── Primitive 1 + 5 + 4: Tool with Progress Notifications and Sampling ───

    @McpTool(
        name = "runBuildTool",
        description = "Runs a Maven or Gradle build in the specified project directory. " +
                      "Emits progress notifications at each build phase. " +
                      "On build failure, requests an AI explanation via MCP sampling. " +
                      "Stores the full output in the project://build-log resource."
    )
    public String runBuildTool(
            // Auto-injected by Spring AI — NOT part of the tool's JSON schema
            McpSyncServerExchange exchange,

            // Extracts progressToken from the MCP request — null if client didn't send one
            @McpProgressToken String progressToken,

            @McpToolParam(description = "Maven goals or Gradle tasks, e.g. 'clean test' or 'build'",
                          required = true)
            String goals,

            @McpToolParam(description = "Absolute path to the project directory to run the build in",
                          required = true)
            String projectPath) {

        // ── Phase 1 ──
        sendLog(exchange, "[1/4] Resolving project: " + projectPath);
        sendProgress(exchange, progressToken, 0.0, 1.0, "[1/4] Resolving project...");

        try {
            List<String> command = buildCommand(goals, projectPath);

            // ── Phase 2 ──
            sendLog(exchange, "[2/4] Starting: " + String.join(" ", command));
            sendProgress(exchange, progressToken, 0.25, 1.0, "[2/4] Build starting...");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(Path.of(projectPath).toFile());
            pb.redirectErrorStream(true);   // merge stderr into stdout

            Process process = pb.start();

            // ── Phase 3 ──
            sendProgress(exchange, progressToken, 0.5, 1.0, "[3/4] Build running...");

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.debug("[build] {}", line);
                }
            }

            int exitCode = process.waitFor();
            String buildOutput = output.toString();
            buildLogHolder.setLastBuildLog(buildOutput);

            // ── Phase 4 ──
            sendProgress(exchange, progressToken, 1.0, 1.0,
                "[4/4] Build complete — exit code: " + exitCode);
            sendLog(exchange, "[4/4] Build finished with exit code " + exitCode);

            // ── Primitive 4: Sampling on failure ─────────────────────────────
            if (exitCode != 0) {
                String aiAnalysis = attemptSampling(exchange, buildOutput);
                String fullResult = buildOutput
                    + "\n\n--- AI Analysis (MCP Sampling) ---\n"
                    + aiAnalysis;
                buildLogHolder.setLastBuildLog(fullResult);
                return fullResult;
            }

            return buildOutput;

        } catch (Exception e) {
            String errorMsg = "Build execution error: " + e.getMessage();
            buildLogHolder.setLastBuildLog(errorMsg);
            log.error("Build failed", e);
            return errorMsg;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Primitive 4: Sampling — ask the connected LLM client to explain the failure.
     * Checks capability first; falls back gracefully if client doesn't support sampling.
     */
    private String attemptSampling(McpSyncServerExchange exchange, String buildOutput) {
        if (exchange == null) return "(sampling unavailable — no exchange)";

        try {
            // Guard: only call createMessage if client advertised sampling capability
            if (exchange.getClientCapabilities() == null ||
                exchange.getClientCapabilities().sampling() == null) {
                return "(sampling not supported by this client)";
            }

            McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
                .systemPrompt("You are a Java build expert.")
                .messages(List.of(new McpSchema.SamplingMessage(
                    McpSchema.Role.USER,
                    new McpSchema.TextContent(
                        "A Maven/Gradle build just failed. Explain the root cause " +
                        "and suggest a concrete fix in 3-4 sentences.\n\n" +
                        "Build output:\n" + buildOutput
                    )
                )))
                .maxTokens(500)
                .build();

            McpSchema.CreateMessageResult result = exchange.createMessage(request);

            if (result.content() instanceof McpSchema.TextContent text) {
                return text.text();
            }
            return "(sampling returned non-text content)";

        } catch (Exception e) {
            log.warn("Sampling failed: {}", e.getMessage());
            return "(sampling unavailable — " + e.getMessage() + ")";
        }
    }

    /**
     * Detects Maven wrapper (mvnw), Gradle wrapper (gradlew), or falls back
     * to a shell command for simple expressions like "echo hello".
     */
    private List<String> buildCommand(String goals, String projectPath) {
        Path dir = Path.of(projectPath);
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        List<String> command = new ArrayList<>();

        if (dir.resolve("mvnw").toFile().exists() || dir.resolve("mvnw.cmd").toFile().exists()) {
            command.add(isWindows ? "mvnw.cmd" : "./mvnw");
        } else if (dir.resolve("gradlew").toFile().exists()) {
            command.add(isWindows ? "gradlew.bat" : "./gradlew");
        } else {
            // No wrapper — treat as a shell command (handles "echo", "mvn", etc.)
            if (isWindows) {
                command.addAll(List.of("cmd", "/c"));
            } else {
                command.addAll(List.of("sh", "-c"));
                command.add(goals);     // pass entire string to shell as one arg
                return command;
            }
        }

        // Split goals into separate tokens for ProcessBuilder
        command.addAll(List.of(goals.split("\\s+")));
        return command;
    }

    /** Primitive 5: Progress notification — silently ignored if client doesn't support it */
    private void sendProgress(McpSyncServerExchange exchange, String token,
                               double progress, double total, String message) {
        if (exchange == null || token == null) return;
        try {
            exchange.progressNotification(
                new ProgressNotification(token, progress, total, message));
        } catch (Exception e) {
            log.debug("Progress notification skipped: {}", e.getMessage());
        }
    }

    /** Logging notification — visible in MCP client log panels */
    private void sendLog(McpSyncServerExchange exchange, String message) {
        if (exchange == null) return;
        try {
            exchange.loggingNotification(
                LoggingMessageNotification.builder()
                    .level(LoggingLevel.INFO)
                    .data(message)
                    .build());
        } catch (Exception e) {
            log.debug("Log notification skipped: {}", e.getMessage());
        }
    }
}
```

**Step 4: Run tests**

```bash
mvn test -Dtest=BuildToolsTest
```

Expected:
```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

**Step 5: Run all tests**

```bash
mvn test
```

Expected: All green.

**Step 6: Commit**

```bash
git add src/
git commit -m "feat: add BuildTools with @McpTool, progress notifications, and MCP sampling"
```

---

## Task 4: ProjectResources — MCP Resources (Primitive 2)

`@McpResource`-annotated methods on a `@Component`. The annotation scanner auto-registers them — no `@Bean` lists needed. Demonstrates both static URIs and URI template (`project://file/{name}`).

**Files:**
- Create: `src/test/java/com/example/devmcp/resources/ProjectResourcesTest.java`
- Create: `src/main/java/com/example/devmcp/resources/ProjectResources.java`

**Step 1: Write the failing test**

```java
package com.example.devmcp.resources;

import com.example.devmcp.BuildLogHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProjectResourcesTest {

    @Autowired
    ProjectResources projectResources;

    @Autowired
    BuildLogHolder buildLogHolder;

    @Test
    void getPomXml_returnsContent_orPlaceholder() {
        String result = projectResources.getPomXml();
        // Either real content or the placeholder — both are valid
        assertThat(result).isNotBlank();
    }

    @Test
    void getReadme_returnsContent_orPlaceholder() {
        String result = projectResources.getReadme();
        assertThat(result).isNotBlank();
    }

    @Test
    void getBuildLog_returnsHolderContent() {
        buildLogHolder.setLastBuildLog("TEST BUILD OUTPUT");
        assertThat(projectResources.getBuildLog()).isEqualTo("TEST BUILD OUTPUT");
    }

    @Test
    void getProjectFile_returnsFileContent(@TempDir Path dir) throws IOException {
        // Note: getProjectFile resolves relative to working directory
        // This test verifies the error-path (file not found)
        String result = projectResources.getProjectFile("nonexistent-file.txt");
        assertThat(result).startsWith("File not found:");
    }
}
```

**Step 2: Run to verify fail**

```bash
mvn test -Dtest=ProjectResourcesTest
```

Expected: FAIL — `ProjectResources` not found.

**Step 3: Create `ProjectResources.java`**

```java
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
```

**Step 4: Run tests**

```bash
mvn test -Dtest=ProjectResourcesTest
```

Expected:
```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

**Step 5: Commit**

```bash
git add src/
git commit -m "feat: add ProjectResources with @McpResource (static + template URIs)"
```

---

## Task 5: DevPrompts — Prompts + Completions (Primitives 3 + bonus)

`@McpPrompt`-annotated methods with `@McpArg` parameters. Plus one `@McpComplete` method that provides argument auto-completion for the `explain-build-error` prompt — this is the "completions" capability.

**Files:**
- Create: `src/test/java/com/example/devmcp/prompts/DevPromptsTest.java`
- Create: `src/main/java/com/example/devmcp/prompts/DevPrompts.java`

**Step 1: Write the failing test**

```java
package com.example.devmcp.prompts;

import io.modelcontextprotocol.sdk.McpSchema.GetPromptResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DevPromptsTest {

    @Autowired
    DevPrompts devPrompts;

    @Test
    void explainBuildError_containsErrorInMessage() {
        GetPromptResult result = devPrompts.explainBuildError("ClassNotFoundException: com.example.Foo");

        String messageText = result.messages().get(0).content().toString();
        assertThat(messageText).contains("ClassNotFoundException");
    }

    @Test
    void codeReview_containsCodeInMessage() {
        GetPromptResult result = devPrompts.codeReview("public void foo() {}");

        String messageText = result.messages().get(0).content().toString();
        assertThat(messageText).contains("public void foo()");
    }

    @Test
    void commitMessage_containsDiffInMessage() {
        GetPromptResult result = devPrompts.commitMessage("+added line\n-removed line");

        String messageText = result.messages().get(0).content().toString();
        assertThat(messageText).contains("+added line");
    }

    @Test
    void completeErrorExamples_filtersOnPrefix() {
        var completions = devPrompts.completeErrorExamples("Class");
        assertThat(completions).contains("ClassNotFoundException");
        assertThat(completions).doesNotContain("BeanCreationException");
    }
}
```

**Step 2: Run to verify fail**

```bash
mvn test -Dtest=DevPromptsTest
```

Expected: FAIL — `DevPrompts` not found.

**Step 3: Create `DevPrompts.java`**

```java
package com.example.devmcp.prompts;

import io.modelcontextprotocol.sdk.McpSchema.GetPromptResult;
import io.modelcontextprotocol.sdk.McpSchema.PromptMessage;
import io.modelcontextprotocol.sdk.McpSchema.Role;
import io.modelcontextprotocol.sdk.McpSchema.TextContent;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpComplete;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP Prompts — Primitive 3.
 *
 * Prompts are server-defined reusable message templates. Clients discover them
 * via prompts/list, then call prompts/get with arguments to get a filled message
 * they inject into the conversation.
 *
 * Think of them as "canned prompt fragments" the AI client pulls from the server.
 * The server controls the exact phrasing; the client just fills in the blanks.
 *
 * Also includes @McpComplete for the "completions" capability — auto-suggests
 * argument values when the client user starts typing into a prompt argument field.
 */
@Component
public class DevPrompts {

    // ── Prompt 1: Explain Build Error ─────────────────────────────────────────

    @McpPrompt(
        name = "explain-build-error",
        description = "Generates a prompt asking the LLM to explain a Maven/Gradle build error " +
                      "and suggest a concrete fix. Pass the full error text as the 'error' argument."
    )
    public GetPromptResult explainBuildError(
            @McpArg(name = "error", description = "Full build error text (stdout/stderr)", required = true)
            String error) {

        return promptResult("Explain build error",
            "You are a Java and Maven/Gradle build expert. " +
            "A build just failed with the following output. " +
            "Explain the root cause clearly and provide a concrete fix " +
            "(code change or config update).\n\n" +
            "Build error:\n" + error
        );
    }

    // ── Prompt 2: Code Review ─────────────────────────────────────────────────

    @McpPrompt(
        name = "code-review",
        description = "Generates a code review prompt for Java source code. " +
                      "Checks for correctness, Spring best practices, readability, and thread safety."
    )
    public GetPromptResult codeReview(
            @McpArg(name = "code", description = "Java source code to review", required = true)
            String code) {

        return promptResult("Java code review",
            "Review the following Java code. Check for:\n" +
            "- Correctness and potential bugs\n" +
            "- Spring Boot best practices\n" +
            "- Readability and naming conventions\n" +
            "- Missing null checks or error handling\n" +
            "- Thread safety issues\n\n" +
            "Code:\n```java\n" + code + "\n```"
        );
    }

    // ── Prompt 3: Commit Message ──────────────────────────────────────────────

    @McpPrompt(
        name = "commit-message",
        description = "Generates a conventional commit message from a git diff. " +
                      "Uses feat/fix/refactor/test/docs/chore types with optional scope."
    )
    public GetPromptResult commitMessage(
            @McpArg(name = "diff", description = "Output of `git diff` or `git diff --staged`", required = true)
            String diff) {

        return promptResult("Generate commit message",
            "Write a conventional commit message for this git diff.\n\n" +
            "Format: <type>(<scope>): <short description under 72 chars>\n" +
            "Types: feat | fix | refactor | test | docs | chore\n" +
            "Scope: optional, e.g. tools, resources, prompts, config\n\n" +
            "Add a blank line and a brief body if the change is non-obvious.\n\n" +
            "Diff:\n```diff\n" + diff + "\n```"
        );
    }

    // ── Completions: Auto-suggest error examples ──────────────────────────────

    /**
     * MCP Completions capability — bonus primitive.
     *
     * When a client user types into the "error" argument of the explain-build-error
     * prompt, the client can call completions/complete to get suggestions.
     * This method filters the known-error list by the typed prefix.
     *
     * @McpComplete links this method to the "explain-build-error" prompt argument.
     */
    @McpComplete(prompt = "explain-build-error")
    public List<String> completeErrorExamples(String prefix) {
        List<String> knownErrors = List.of(
            "ClassNotFoundException",
            "NoSuchBeanDefinitionException",
            "BeanCreationException",
            "UnsatisfiedDependencyException",
            "NullPointerException",
            "COMPILATION ERROR",
            "BUILD FAILURE",
            "package does not exist",
            "cannot find symbol",
            "Failed to execute goal"
        );
        return knownErrors.stream()
            .filter(e -> e.toLowerCase().startsWith(prefix.toLowerCase()))
            .toList();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private GetPromptResult promptResult(String description, String userMessage) {
        return new GetPromptResult(description,
            List.of(new PromptMessage(Role.USER, new TextContent(userMessage))));
    }
}
```

**Step 4: Run tests**

```bash
mvn test -Dtest=DevPromptsTest
```

Expected:
```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

**Step 5: Run full suite**

```bash
mvn test
```

Expected: All tests green, 0 failures.

**Step 6: Commit**

```bash
git add src/
git commit -m "feat: add DevPrompts with @McpPrompt, @McpArg, and @McpComplete"
```

---

## Task 6: README.md

Write a comprehensive README explaining all MCP concepts and components.

**Step 1: Create `README.md`** — see Task 7 below (written as a separate task for clarity).

**Step 2: Commit**

```bash
git add README.md
git commit -m "docs: add comprehensive README covering all MCP primitives"
```

---

## Task 7: Integration Test with MCP Inspector

Verify all primitives are live and callable end-to-end.

**Step 1: Start the server**

```bash
mvn spring-boot:run
```

Expected console:
```
Registered tools:     2  (readFileTool, runBuildTool)
Registered resources: 4  (project://pom.xml, project://readme, project://build-log, project://file/{name})
Registered prompts:   3  (explain-build-error, code-review, commit-message)
Tomcat started on port 8080
Started DevMcpApplication
```

**Step 2: Launch MCP Inspector**

```bash
npx -y @modelcontextprotocol/inspector
```

**Step 3: Connect**

- Transport: `Streamable HTTP`
- URL: `http://localhost:8080/mcp`
- Connect → `Connected — dev-mcp-server v1.0.0`

**Step 4: Test each primitive tab**

| Tab | What to verify |
|-----|----------------|
| Tools | `readFileTool` and `runBuildTool` listed |
| Resources | All 4 URIs listed; click `project://build-log` → read it |
| Prompts | All 3 listed; call `explain-build-error` with `error=ClassNotFoundException` |

**Step 5: Verify runBuildTool progress**

- Call `runBuildTool` with `goals=mvn --version`, `projectPath=<absolute path to week19>`
- In the Inspector, watch the logging/progress notifications appear
- After call: click `project://build-log` resource → it should now contain the build output

**Step 6: Connect Claude Desktop**

Edit `%APPDATA%\Claude\claude_desktop_config.json` (Windows):

```json
{
  "mcpServers": {
    "dev-mcp-server": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

Restart Claude Desktop. Ask: *"Read the project://build-log resource"* — Claude will use `readFileTool` or the resource directly.

---

## Summary: All MCP Primitives Covered

| Primitive | Annotation | Class | Lines |
|-----------|-----------|-------|-------|
| **1. Tools** | `@McpTool` + `@McpToolParam` | `BuildTools` | `readFileTool`, `runBuildTool` |
| **2. Resources** | `@McpResource` | `ProjectResources` | 3 static + 1 template URI |
| **3. Prompts** | `@McpPrompt` + `@McpArg` | `DevPrompts` | 3 developer prompt templates |
| **4. Sampling** | `exchange.createMessage()` | `BuildTools.attemptSampling()` | Server-initiated LLM inference |
| **5. Progress** | `@McpProgressToken` + `exchange.progressNotification()` | `BuildTools.runBuildTool()` | 4-phase build progress |
| **Bonus: Completions** | `@McpComplete` | `DevPrompts` | Auto-suggests error class names |

### Key Spring AI MCP Configuration

```properties
spring.ai.mcp.server.type=SYNC          # SYNC for blocking methods / ASYNC for Mono/Flux
spring.ai.mcp.server.protocol=STREAMABLE # SSE | STREAMABLE | STATELESS
spring.ai.mcp.server.annotation-scanner.enabled=true
spring.ai.mcp.server.annotation-scanner.packages=com.example.devmcp
```

### Auto-Injected Special Parameters (excluded from tool JSON schema)

| Parameter | How to get it | Use case |
|-----------|---------------|---------|
| `McpSyncServerExchange exchange` | Declare as method param | Logging, progress, sampling, capability check |
| `@McpProgressToken String token` | Declare annotated String param | Pass to `exchange.progressNotification()` |
| `McpMeta meta` | Declare as method param | Read request metadata |
| `McpTransportContext ctx` | Declare as method param | Lightweight stateless context |
