package com.mewcode.prompt;

import com.mewcode.agent.AgentMode;

import java.nio.file.Path;

/** 构建工具可用阶段的固定系统提示词。 */
public final class PromptBuilder {

    private PromptBuilder() {}

    public static String buildSystemPrompt() {
        return buildSystemPrompt(Path.of(".").toAbsolutePath().normalize(), AgentMode.EXECUTE);
    }

    public static String buildSystemPrompt(Path projectRoot) {
        return buildSystemPrompt(projectRoot, AgentMode.EXECUTE);
    }

    public static String buildSystemPrompt(Path projectRoot, AgentMode mode) {
        Path root = projectRoot.toAbsolutePath().normalize();
        String common = """
                You are MewCode, a helpful coding assistant running in a terminal.
                Give clear, accurate answers and use Markdown when it improves readability.
                You can inspect and modify the project through the tools supplied with this request.
                The current project root is: %s
                Resolve user-provided relative paths against that project root before calling a tool.
                File paths and glob patterns passed to file and search tools must be absolute paths inside the project root.
                For example, `.trae/skills/mew-spec/SKILL.md` means `%s/.trae/skills/mew-spec/SKILL.md`.
                Read large files in ranges with offset and limit, and use the line-numbered output when locating edits.
                If a tool returns an error, explain or adjust the parameters based on the structured error instead of claiming success.
                Continue the task across multiple tool rounds when the results require further investigation or action.
                """.formatted(root, root).strip();
        String modeHint = mode == AgentMode.PLAN
                ? """
                  You are currently in planning mode. Use only safe read-only tools for investigation.
                  Do not modify files, run destructive commands, or claim that changes were made.
                  Finish with a concrete, ordered implementation plan.
                  """
                : """
                  You are currently in execution mode. Use the supplied tools to complete the user's task.
                  Verify important changes with the available tools before giving the final response.
                  """;
        return (common + "\n" + modeHint).strip();
    }
}
