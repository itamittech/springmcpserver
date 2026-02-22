package com.example.devmcp.tools;

import com.example.devmcp.BuildLogHolder;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpProgressToken;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import io.modelcontextprotocol.server.McpSyncServerExchange;
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
