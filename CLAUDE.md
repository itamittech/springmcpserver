# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run the server (starts on port 8080)
./mvnw spring-boot:run

# Build and package
./mvnw package

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=BuildLogHolderTest

# Test the running server with MCP Inspector
npx -y @modelcontextprotocol/inspector
# → Transport: Streamable HTTP, URL: http://localhost:8080/mcp
```

**Prerequisites:** Java 25, Maven 3.9+. Dependencies are pulled from Spring Milestone and Snapshot repositories (configured in pom.xml) — do not remove those repository blocks.

## Architecture

This is a **Spring Boot 4.0.3 / Spring AI 2.0.0-M2 MCP server** that implements all MCP primitives. The MCP endpoint is `POST http://localhost:8080/mcp` (Streamable HTTP transport).

### Registration model

Spring AI's annotation scanner (`spring.ai.mcp.server.annotation-scanner`) auto-discovers `@McpTool`, `@McpResource`, `@McpPrompt`, and `@McpComplete` on any `@Component` bean in the `com.example.devmcp` package. No `@Configuration` class or programmatic registration is needed.

### Key classes

| Class | Role |
|-------|------|
| `BuildLogHolder` | `@Component` in-memory store for the last build output. `volatile` field — one writer (`BuildTools`), many readers (`ProjectResources`). |
| `BuildTools` | Two `@McpTool` methods: `readFileTool` (simple) and `runBuildTool` (emits progress notifications + requests AI explanation via sampling on failure). |
| `ProjectResources` | Four `@McpResource` methods: static URIs (`project://pom.xml`, `project://readme`, `project://build-log`) and a template URI (`project://file/{name}`). |
| `DevPrompts` | Three `@McpPrompt` methods plus one `@McpComplete` method (auto-suggest for `explain-build-error`'s `error` argument). |

### Auto-injected parameters (excluded from JSON schema)

`@McpTool`/`@McpResource`/`@McpPrompt` methods can declare these parameters; Spring AI injects them and hides them from clients:

- `McpSyncServerExchange exchange` — logging notifications, progress notifications, sampling (`exchange.createMessage()`), capability checks
- `@McpProgressToken String progressToken` — null if client didn't send one; always null-check before calling `sendProgress`

### Data flow for `runBuildTool`

1. Emits 4 progress notifications + logging notifications as build phases advance
2. Runs the build via `ProcessBuilder` (auto-detects `mvnw`, `gradlew`, or falls back to shell)
3. Stores full output in `BuildLogHolder` (readable via `project://build-log` resource)
4. On non-zero exit: calls `exchange.createMessage()` (MCP Sampling) to ask the connected LLM client for an AI analysis; appends result to the return value

### Transport configuration (`application.properties`)

- `spring.ai.mcp.server.protocol=STREAMABLE` — use `STDIO` for Claude Desktop process mode, `SSE` for legacy, `STATELESS` for stateless HTTP
- `spring.ai.mcp.server.type=SYNC` — change to `ASYNC` and return `Mono<String>` from tool methods when switching to WebFlux
