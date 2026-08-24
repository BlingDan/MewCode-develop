package com.mewcode.llm;

/** Protocol-neutral events emitted by a streaming LLM request. */
public sealed interface StreamEvent {
    record TextDelta(String text) implements StreamEvent {}
    record ThinkingDelta(String text, String signature) implements StreamEvent {
        public ThinkingDelta(String text) {
            this(text, "");
        }

        public ThinkingDelta {
            text = java.util.Objects.requireNonNullElse(text, "");
            signature = java.util.Objects.requireNonNullElse(signature, "");
        }
    }
    record Usage(java.util.OptionalLong inputTokens,
                 java.util.OptionalLong outputTokens) implements StreamEvent {
        public Usage {
            java.util.Objects.requireNonNull(inputTokens, "inputTokens");
            java.util.Objects.requireNonNull(outputTokens, "outputTokens");
        }
    }
    record ToolCallComplete(String toolUseId, String toolName,
                            java.util.Map<String, Object> arguments) implements StreamEvent {}
    record ToolCallParseError(String toolUseId, String toolName, String message) implements StreamEvent {}
    record StreamEnd(String stopReason) implements StreamEvent {}
    record Error(String message) implements StreamEvent {}
}
