package com.example.devmcp.prompts;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
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
