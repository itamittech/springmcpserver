# Design: week19-dev-mcp-server

**Date:** 2026-02-22
**Status:** Approved

---

## Overview

A Maven-based Spring Boot MCP server demonstrating all five MCP primitives (Tools, Resources, Prompts, Sampling, Progress Notifications) around a Developer Productivity theme. Exposes a Streamable HTTP endpoint at `localhost:8080/mcp` that both Claude Desktop and MCP Inspector can connect to.

---

## Goals

1. Combine learnings from week17 (STDIO, simple tools) and week18 (Streamable HTTP, external APIs) into one cohesive Maven project.
2. Demonstrate every MCP server primitive available in Spring AI 2.0.0-M2.
3. Use a coherent domain — developer productivity / build tooling — where all primitives feel natural, not contrived.

---

## Project Identity

| Property     | Value                          |
|--------------|--------------------------------|
| Directory    | `week19-dev-mcp-server/`       |
| Artifact ID  | `week19-dev-mcp-server`        |
| Group ID     | `com.example`                  |
| Package      | `com.example.devmcp`           |
| Build        | Maven (`pom.xml`)              |
| Spring Boot  | 4.0.3                          |
| Spring AI    | 2.0.0-M2                       |
| Java         | 25                             |

---

## Transport

**Streamable HTTP** — single configuration, all clients use the same endpoint.

```properties
spring.ai.mcp.server.protocol=STREAMABLE
server.port=8080
```

**Claude Desktop** connects via `url` (not `command`):
```json
{
  "mcpServers": {
    "dev-mcp-server": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

**MCP Inspector** connects via `Streamable HTTP` → `http://localhost:8080/mcp`.

---

## Package Structure

```
week19-dev-mcp-server/
├── pom.xml
├── docs/plans/
│   └── 2026-02-22-dev-mcp-server-design.md
└── src/main/
    ├── java/com/example/devmcp/
    │   ├── DevMcpApplication.java
    │   ├── tools/
    │   │   └── BuildTools.java            ← Primitive 1: Tools + Primitive 5: Progress
    │   ├── resources/
    │   │   └── ProjectResourceConfig.java ← Primitive 2: Resources
    │   ├── prompts/
    │   │   └── DevPromptConfig.java       ← Primitive 3: Prompts
    │   └── sampling/
    │       └── BuildErrorAnalyzer.java    ← Primitive 4: Sampling
    └── resources/
        └── application.properties
```

---

## MCP Primitives

### Primitive 1: Tools (`BuildTools.java`)

Two `@McpTool` methods. The build runner also accepts `McpSyncServerExchange` to emit progress.

| Tool              | Signature                                                  | Description                                         |
|-------------------|------------------------------------------------------------|-----------------------------------------------------|
| `runBuildTool`    | `String runBuildTool(String goals, String projectPath, McpSyncServerExchange exchange)` | Runs `mvn <goals>` or `./gradlew <tasks>`, streams progress notifications, delegates to `BuildErrorAnalyzer` on failure |
| `readFileTool`    | `String readFileTool(String filePath)`                     | Reads any file from the filesystem (carried forward from week17) |

### Primitive 2: Resources (`ProjectResourceConfig.java`)

A `@Bean` of type `List<McpServerFeatures.SyncResourceRegistration>`. These are server-side data sources the LLM can browse and read.

| URI                  | MIME Type  | Content                                    |
|----------------------|------------|--------------------------------------------|
| `project://pom.xml`  | `text/xml` | The server project's own `pom.xml`         |
| `project://readme`   | `text/markdown` | `README.md` if present, else placeholder |
| `project://build-log`| `text/plain` | Output of the most recent `runBuildTool` call, held in memory |

### Primitive 3: Prompts (`DevPromptConfig.java`)

A `@Bean` of type `List<McpServerFeatures.SyncPromptRegistration>`. Reusable prompt templates the LLM client requests by name.

| Prompt Name           | Argument  | System Message Template                                      |
|-----------------------|-----------|--------------------------------------------------------------|
| `explain-build-error` | `error`   | "You are a Java expert. Explain this Maven/Gradle build error and suggest a fix: {error}" |
| `code-review`         | `code`    | "Review this Java code for correctness, readability, and Spring best practices: {code}" |
| `commit-message`      | `diff`    | "Write a conventional commit message (feat/fix/refactor) for this git diff: {diff}" |

### Primitive 4: Sampling (`BuildErrorAnalyzer.java`)

When `runBuildTool` exits with a non-zero code, `BuildErrorAnalyzer.analyze(exchange, buildOutput)` fires a `McpSchema.CreateMessageRequest` back to the connected LLM client. The LLM's explanation is appended to the tool response.

```
Build process exits with code != 0
    → BuildErrorAnalyzer.analyze(exchange, stderr)
    → exchange.createMessage(CreateMessageRequest)
    → LLM client (Claude) returns explanation
    → Tool returns: build output + "\n--- AI Analysis ---\n" + explanation
```

This is server-initiated inference — the MCP server is the one asking Claude for help.

### Primitive 5: Progress Notifications (inside `BuildTools.java`)

`runBuildTool` emits logging notifications via `McpSyncServerExchange` at key lifecycle points:

```
[1/4] Resolving project path...
[2/4] Starting build: mvn clean test
[3/4] Build running... (streaming stdout)
[4/4] Build complete — exit code: 0
```

---

## Key Dependencies (`pom.xml`)

```xml
<!-- MCP Server with WebMVC transport -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>

<!-- WebFlux for WebClient -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>

<!-- .env file support -->
<dependency>
    <groupId>me.paulschwarz</groupId>
    <artifactId>spring-dotenv</artifactId>
    <version>4.0.0</version>
</dependency>
```

Spring AI version managed via BOM: `spring-ai-bom:2.0.0-M2`.
Spring Boot parent: `spring-boot-starter-parent:4.0.3`.

---

## application.properties

```properties
spring.ai.mcp.server.protocol=STREAMABLE
server.port=8080
spring.ai.mcp.server.name=dev-mcp-server
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.annotation-scanner.packages=com.example.devmcp
```

---

## MCP Concepts Demonstrated (Learning Map)

| Concept                          | Location                              |
|----------------------------------|---------------------------------------|
| Streamable HTTP transport        | `application.properties`              |
| `@McpTool` annotation scanning   | `BuildTools.java`                     |
| Progress notifications           | `BuildTools.java` + `McpSyncServerExchange` |
| MCP Resources (server data)      | `ProjectResourceConfig.java`          |
| MCP Prompts (server templates)   | `DevPromptConfig.java`                |
| MCP Sampling (server → LLM)      | `BuildErrorAnalyzer.java`             |
| Maven project setup for Spring AI| `pom.xml`                             |
| Claude Desktop HTTP connection   | `claude_desktop_config.json`          |
| MCP Inspector HTTP connection    | Runtime testing                       |

---

## Out of Scope

- Authentication / API key protection on the MCP endpoint
- Persistence of build logs across restarts (in-memory only)
- SSE (old transport) — using Streamable HTTP only
- STDIO transport — not needed since Streamable HTTP works with Claude Desktop
