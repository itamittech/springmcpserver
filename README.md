# week19-dev-mcp-server

A **Maven-based Spring Boot MCP server** that demonstrates every MCP server primitive available in Spring AI 2.0.0-M2. Built around a **developer productivity** theme — the server knows how to run builds, read project files, and provide reusable developer prompt templates.

---

## What is MCP?

The **Model Context Protocol (MCP)** is an open standard (by Anthropic) that lets LLMs interact with external systems in a structured, capability-negotiated way. Instead of building custom integrations per-model, you build one MCP server and any MCP-compatible client (Claude Desktop, MCP Inspector, custom apps) connects to it.

```
┌─────────────────┐     JSON-RPC over HTTP/SSE     ┌─────────────────────────┐
│  MCP Client     │ ◄─────────────────────────────► │  MCP Server (this app)  │
│                 │                                  │                         │
│  Claude Desktop │  initialize → capabilities       │  Tools, Resources,      │
│  MCP Inspector  │  tools/list → tool definitions  │  Prompts, Completions,  │
│  Custom app     │  tools/call → tool execution    │  Progress, Sampling     │
└─────────────────┘  resources/read → file content  └─────────────────────────┘
                     prompts/get  → prompt template
```

**Transports supported by Spring AI:**

| Transport | Property | Use case |
|-----------|----------|---------|
| STDIO | `spring.ai.mcp.server.stdio=true` | Claude Desktop (process-based) |
| SSE | `spring.ai.mcp.server.protocol=SSE` | HTTP + Server-Sent Events (legacy) |
| **Streamable HTTP** | `spring.ai.mcp.server.protocol=STREAMABLE` | Modern HTTP, optional SSE, multi-client |
| Stateless | `spring.ai.mcp.server.protocol=STATELESS` | Stateless microservices |

This project uses **Streamable HTTP** — one server process, many clients, no stdin/stdout pipe constraints.

---

## MCP Primitives — What This Project Covers

MCP defines six server-side primitives. This project implements all of them:

| # | Primitive | What it is | Where in this project |
|---|-----------|-----------|----------------------|
| 1 | **Tools** | Callable functions the LLM can invoke | `BuildTools.java` — `readFileTool`, `runBuildTool` |
| 2 | **Resources** | URI-addressable data the LLM can read | `ProjectResources.java` — pom.xml, readme, build-log, file template |
| 3 | **Prompts** | Server-defined reusable message templates | `DevPrompts.java` — explain-build-error, code-review, commit-message |
| 4 | **Sampling** | Server asks the LLM client to run inference | `BuildTools.attemptSampling()` — explains failed builds |
| 5 | **Progress** | Long-running tools emit incremental updates | `BuildTools.runBuildTool()` — 4-phase build progress |
| + | **Completions** | Auto-suggests argument values for prompts | `DevPrompts.completeErrorExamples()` — error class name hints |

---

## Project Structure

```
week19-dev-mcp-server/
├── pom.xml                                     Maven build (not Gradle)
├── README.md                                   This file
├── docs/plans/
│   ├── 2026-02-22-dev-mcp-server-design.md     Design doc (approved)
│   └── 2026-02-22-dev-mcp-server.md            Implementation plan
└── src/main/java/com/example/devmcp/
    ├── DevMcpApplication.java                  @SpringBootApplication entry point
    ├── BuildLogHolder.java                     @Component — shared build output state
    ├── tools/
    │   └── BuildTools.java                     @McpTool ×2, progress, sampling
    ├── resources/
    │   └── ProjectResources.java               @McpResource ×4
    └── prompts/
        └── DevPrompts.java                     @McpPrompt ×3, @McpComplete ×1
```

**Why no `@Configuration` beans for registration?**
Spring AI's annotation scanner auto-discovers `@McpTool`, `@McpResource`, `@McpPrompt`, and `@McpComplete` on any `@Component` bean and registers them with the MCP server. No programmatic `SyncToolRegistration` / `SyncResourceRegistration` lists are needed — that was the old low-level approach.

---

## MCP Primitives In Depth

### 1. Tools — `BuildTools.java`

Tools are the most-used primitive. The LLM calls them like functions, passing JSON arguments. Spring AI generates the JSON schema automatically from the method signature.

```java
@Component
public class BuildTools {

    @McpTool(name = "readFileTool", description = "Reads any text file from the filesystem")
    public String readFileTool(
            @McpToolParam(description = "Absolute path to the file", required = true)
            String filePath) { ... }

    @McpTool(name = "runBuildTool", description = "Runs a Maven/Gradle build with progress + sampling")
    public String runBuildTool(
            McpSyncServerExchange exchange,   // ← auto-injected, NOT in schema
            @McpProgressToken String token,   // ← auto-injected, NOT in schema
            @McpToolParam(...) String goals,
            @McpToolParam(...) String projectPath) { ... }
}
```

Key annotation details:
- `@McpTool(name, description)` — declares the method as an MCP tool
- `@McpToolParam(description, required)` — documents each user-visible parameter
- `McpSyncServerExchange` injected as method param — gives access to logging, progress, sampling
- `@McpProgressToken String token` — injects the client's progress tracking token (may be null)

**JSON schema seen by the LLM for `runBuildTool`:**
```json
{
  "type": "object",
  "properties": {
    "goals":       { "type": "string", "description": "Maven goals or Gradle tasks" },
    "projectPath": { "type": "string", "description": "Absolute project directory path" }
  },
  "required": ["goals", "projectPath"]
}
```
> `exchange` and `token` are **invisible** to the LLM — Spring AI excludes them automatically.

---

### 2. Resources — `ProjectResources.java`

Resources are server-side data the LLM can read. The client discovers them via `resources/list` and reads them via `resources/read`. Unlike tools, the LLM doesn't "call" a resource — it reads it like a document.

```java
@Component
public class ProjectResources {

    // Static URI — no path variables, no method parameters
    @McpResource(uri = "project://pom.xml", name = "Maven POM",
                 description = "Project dependencies and build config")
    public String getPomXml() {
        return Files.readString(Path.of("pom.xml"));
    }

    // Template URI — {name} maps automatically to the String parameter
    @McpResource(uri = "project://file/{name}", name = "Project File",
                 description = "Any file relative to the project root")
    public String getProjectFile(String name) {
        return Files.readString(Path.of(name));
    }

    // Live data — reads from BuildLogHolder (populated by runBuildTool)
    @McpResource(uri = "project://build-log", name = "Build Log",
                 description = "Output of the most recent build")
    public String getBuildLog() {
        return buildLogHolder.getLastBuildLog();
    }
}
```

**URI patterns:**
- `project://pom.xml` — static, client reads it by exact URI
- `project://file/{name}` — template, client passes the file name in the URI
- All return `String` (auto-wrapped); you can also return `ReadResourceResult` for full MIME control

---

### 3. Prompts — `DevPrompts.java`

Prompts are reusable message templates stored on the server. The LLM client fetches them by name and injects the returned messages into the conversation. This lets the server control the exact phrasing of developer-facing prompts.

```java
@Component
public class DevPrompts {

    @McpPrompt(name = "explain-build-error",
               description = "Explains a Maven/Gradle build error and suggests a fix")
    public GetPromptResult explainBuildError(
            @McpArg(name = "error", description = "Full build error text", required = true)
            String error) {
        return new GetPromptResult("Explain build error", List.of(
            new PromptMessage(Role.USER, new TextContent(
                "You are a Java expert. Explain this error and suggest a fix:\n\n" + error
            ))
        ));
    }
}
```

**Three prompts registered:**
| Name | Argument | What the LLM is asked to do |
|------|----------|----------------------------|
| `explain-build-error` | `error` | Diagnose and fix a Maven/Gradle failure |
| `code-review` | `code` | Review Java code for correctness + Spring best practices |
| `commit-message` | `diff` | Generate a conventional commit message from `git diff` |

---

### 4. Sampling — `BuildTools.attemptSampling()`

Sampling is a server-initiated call to the LLM. The MCP *server* sends a `CreateMessageRequest` to the *client* (e.g. Claude Desktop) asking it to run inference and return a result. This is the reverse of the normal flow.

In this project: when `runBuildTool` exits with a non-zero code, the server sends the build output to Claude and asks it to explain the error. Claude's response is appended to the tool result.

```java
// Inside runBuildTool, after exitCode != 0:

if (exchange.getClientCapabilities().sampling() != null) {
    McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
        .systemPrompt("You are a Java build expert.")
        .messages(List.of(new McpSchema.SamplingMessage(
            McpSchema.Role.USER,
            new McpSchema.TextContent("Explain this build failure:\n\n" + buildOutput)
        )))
        .maxTokens(500)
        .build();

    McpSchema.CreateMessageResult result = exchange.createMessage(request);
    String explanation = ((McpSchema.TextContent) result.content()).text();
    return buildOutput + "\n\n--- AI Analysis ---\n" + explanation;
}
```

**Data flow:**
```
runBuildTool() → build fails → server calls exchange.createMessage()
    → MCP client (Claude Desktop) runs inference
    → returns explanation to server
    → server appends explanation to tool result
    → LLM reads combined result
```

> Sampling requires the client to have advertised the `sampling` capability during the `initialize` handshake. Always guard with `exchange.getClientCapabilities().sampling() != null`.

---

### 5. Progress Notifications — `BuildTools.runBuildTool()`

Long-running tools can emit progress updates so the client can show a progress bar or streaming log. Two mechanics work together:

1. **`@McpProgressToken String progressToken`** — Spring AI injects the client's progress tracking token (clients that care about progress include it in the tool call request; others don't, so always null-check)

2. **`exchange.progressNotification(new ProgressNotification(token, progress, total, message))`** — sends the progress event

3. **`exchange.loggingNotification(LoggingMessageNotification.builder().level(INFO).data("...").build())`** — sends a structured log line (visible in client log panels)

```java
@McpTool(name = "runBuildTool", ...)
public String runBuildTool(
        McpSyncServerExchange exchange,
        @McpProgressToken String progressToken,
        ...) {

    // Phase 1
    exchange.loggingNotification(LoggingMessageNotification.builder()
        .level(LoggingLevel.INFO).data("[1/4] Resolving project...").build());
    if (progressToken != null) {
        exchange.progressNotification(
            new ProgressNotification(progressToken, 0.0, 1.0, "[1/4] Resolving..."));
    }
    // ... phases 2, 3, 4
}
```

**What the client sees:**
```
[1/4] Resolving project: /path/to/project
[2/4] Starting: ./mvnw clean test
[3/4] Build running...
[4/4] Build complete — exit code: 0
```

---

### Bonus: Completions — `DevPrompts.completeErrorExamples()`

Completions provide argument auto-suggestions. When a user starts typing into a prompt argument in an MCP-aware client, the client calls `completions/complete` and shows suggestions.

```java
// Linked to the "explain-build-error" prompt via prompt name
@McpComplete(prompt = "explain-build-error")
public List<String> completeErrorExamples(String prefix) {
    return List.of(
        "ClassNotFoundException", "NoSuchBeanDefinitionException",
        "BeanCreationException", "COMPILATION ERROR", ...
    ).stream()
     .filter(e -> e.toLowerCase().startsWith(prefix.toLowerCase()))
     .toList();
}
```

---

## Configuration Reference

### `application.properties`

```properties
# Transport: STREAMABLE (HTTP POST + optional SSE) on port 8080
spring.ai.mcp.server.protocol=STREAMABLE
server.port=8080

# Server type: SYNC registers non-reactive methods; ASYNC registers Mono/Flux methods
spring.ai.mcp.server.type=SYNC

# Identity (shown in MCP Inspector after "Connected")
spring.ai.mcp.server.name=dev-mcp-server
spring.ai.mcp.server.version=1.0.0

# Annotation scanner: discovers @McpTool/@McpResource/@McpPrompt/@McpComplete
spring.ai.mcp.server.annotation-scanner.enabled=true
spring.ai.mcp.server.annotation-scanner.packages=com.example.devmcp
```

**All server capabilities are enabled by default.** No explicit `spring.ai.mcp.server.capabilities.*` configuration is needed unless you want to disable something.

### Auto-Injected Special Parameters

These can be declared in any `@McpTool`, `@McpResource`, or `@McpPrompt` method. Spring AI injects them automatically and **excludes them from the JSON schema** shown to clients.

| Parameter | Type | Use case |
|-----------|------|---------|
| Exchange | `McpSyncServerExchange` | Logging, progress, sampling, capability check |
| Progress token | `@McpProgressToken String` | `null` if client didn't send one |
| Metadata | `McpMeta` | Read arbitrary request metadata |
| Transport context | `McpTransportContext` | Lightweight stateless context |

---

## Running the Server

**Prerequisites:** Java 25, Maven 3.9+

```bash
cd week19-dev-mcp-server

# Run the server
mvn spring-boot:run

# Expected startup output:
# Registered tools:     2
# Registered resources: 4
# Registered prompts:   3
# Tomcat started on port 8080
# Started DevMcpApplication
```

---

## Connecting Clients

### MCP Inspector (recommended for development)

```bash
npx -y @modelcontextprotocol/inspector
```

- Transport: `Streamable HTTP`
- URL: `http://localhost:8080/mcp`
- Click **Connect** → `Connected — dev-mcp-server v1.0.0`

Explore each tab: **Tools**, **Resources**, **Prompts**, **Notifications**

### Claude Desktop

Edit `%APPDATA%\Claude\claude_desktop_config.json` (Windows) or `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS):

```json
{
  "mcpServers": {
    "dev-mcp-server": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

Restart Claude Desktop. The hammer icon (🔨) appears when tools are available.

### Claude Code

Add the following configuration to your Claude Code settings:

```json
{
  "mcpServers": {
    "my-local-server": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "http://localhost:8080/mcp"
      ]
    }
  },
  "preferences": {
    "sidebarMode": "chat",
    "coworkScheduledTasksEnabled": false
  }
}
```

**Example prompts to try in Claude Desktop:**
- *"Run `mvn --version` in `C:\path\to\week19-dev-mcp-server`"* → calls `runBuildTool`, shows progress
- *"Read the project://readme resource"* → reads README.md via MCP resource
- *"Use the code-review prompt to review this code: `public void foo() {}`"* → uses MCP prompt template

---

## Architecture Summary

```
                           Claude Desktop / MCP Inspector
                                        │
                              HTTP POST /mcp
                                        │
                            ┌───────────▼────────────┐
                            │   Spring Boot App       │
                            │   (port 8080)           │
                            │                         │
                            │  @McpTool               │
                            │    readFileTool  ───────┼─► Files.readString()
                            │    runBuildTool  ───────┼─► ProcessBuilder (mvn/gradle)
                            │                │        │     │
                            │                │        │     ├─ progress notifications ──► client
                            │                │        │     ├─ logging notifications  ──► client
                            │                └────────┼─► sampling (createMessage)   ◄── client
                            │                         │
                            │  @McpResource           │
                            │    project://pom.xml ───┼─► reads pom.xml
                            │    project://readme  ───┼─► reads README.md
                            │    project://build-log ─┼─► BuildLogHolder (in-memory)
                            │    project://file/{n} ──┼─► reads any project file
                            │                         │
                            │  @McpPrompt             │
                            │    explain-build-error ─┼─► GetPromptResult (filled template)
                            │    code-review       ───┼─► GetPromptResult (filled template)
                            │    commit-message    ───┼─► GetPromptResult (filled template)
                            │                         │
                            │  @McpComplete           │
                            │    completeErrorExamples┼─► List<String> suggestions
                            └─────────────────────────┘
```

---

## Spring AI MCP Annotation Quick Reference

```java
// TOOLS
@McpTool(name = "toolName", description = "...")
public String myTool(
    @McpToolParam(description = "...", required = true) String param,
    McpSyncServerExchange exchange,   // special — auto-injected
    @McpProgressToken String token)   // special — auto-injected

// RESOURCES (static URI)
@McpResource(uri = "scheme://path", name = "...", description = "...")
public String myResource() { ... }

// RESOURCES (URI template)
@McpResource(uri = "scheme://path/{id}", name = "...", description = "...")
public String myResource(String id) { ... }

// PROMPTS
@McpPrompt(name = "...", description = "...")
public GetPromptResult myPrompt(
    @McpArg(name = "arg", description = "...", required = true) String arg)

// COMPLETIONS
@McpComplete(prompt = "prompt-name")
public List<String> myCompletion(String prefix) { ... }
```

All annotations in: `org.springaicommunity.mcp.annotation.*`

---

## Tech Stack

| Technology | Version | Purpose |
|-----------|---------|---------|
| Spring Boot | 4.0.3 | Application framework |
| Spring AI | 2.0.0-M2 | MCP server auto-configuration + annotation processing |
| `spring-ai-starter-mcp-server-webmvc` | 2.0.0-M2 | Streamable HTTP transport on WebMVC/Tomcat |
| Java | 25 | Runtime |
| Maven | 3.9+ | Build tool |
| JUnit 5 + Mockito | (via Boot BOM) | Testing |

---

## What to Learn Next

| Topic | How to explore |
|-------|---------------|
| STDIO transport | Change `protocol=STREAMABLE` → `stdio=true`, rebuild as JAR, connect from Claude Desktop via `command` |
| ASYNC server | Change `type=SYNC` → `type=ASYNC`, rewrite tools to return `Mono<String>` |
| WebFlux transport | Switch to `spring-ai-starter-mcp-server-webflux` dependency |
| Stateless HTTP | Change `protocol=STREAMABLE` → `protocol=STATELESS` (no session state) |
| MCP Client role | Add `spring-ai-starter-mcp-client` to connect to *another* MCP server from this app |
| Elicitation | Add `@McpElicitation` on client side — server requests extra info from user mid-tool |
| Roots | Server sends `roots/list` request to understand the client's file system boundaries |
