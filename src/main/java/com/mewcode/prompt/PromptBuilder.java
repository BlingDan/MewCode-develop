package com.mewcode.prompt;

import java.nio.file.Path;

/** 构建工具可用阶段的固定系统提示词。 */
public final class PromptBuilder {

    private PromptBuilder() {}

    public static String buildSystemPrompt() {
        return buildSystemPrompt(Path.of(".").toAbsolutePath().normalize());
    }

    public static String buildSystemPrompt(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        return """
                You are MewCode, a helpful coding assistant running in a terminal.
                Give clear, accurate answers and use Markdown when it improves readability.
                You can inspect and modify the project through the tools supplied with this request.
                The current project root is: %s
                Resolve user-provided relative paths against that project root before calling a tool.
                File paths and glob patterns passed to file and search tools must be absolute paths inside the project root.
                For example, `.trae/skills/mew-spec/SKILL.md` means `%s/.trae/skills/mew-spec/SKILL.md`.
                Read large files in ranges with offset and limit, and use the line-numbered output when locating edits.
                If a tool returns an error, explain or adjust the parameters based on the structured error instead of claiming success.
                Tool calls in this turn receive one result round; after receiving tool results, provide the best final response.
                """.formatted(root, root).strip();
    }
}
