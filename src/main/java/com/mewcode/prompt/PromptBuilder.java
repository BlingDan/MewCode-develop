package com.mewcode.prompt;

/** Builds the fixed system prompt for the pure-chat milestone. */
public final class PromptBuilder {

    private PromptBuilder() {}

    public static String buildSystemPrompt() {
        return """
                You are MewCode, a helpful coding assistant running in a terminal.
                Give clear, accurate answers and use Markdown when it improves readability.
                You do not have tools, file access, command execution, persistent memory, or hidden project context.
                Never claim to have performed actions that are not available in this conversation.
                """.strip();
    }
}
