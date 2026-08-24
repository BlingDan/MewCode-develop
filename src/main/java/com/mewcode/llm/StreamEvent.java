package com.mewcode.llm;

/** Protocol-neutral events emitted by a streaming LLM request. */
public sealed interface StreamEvent {
    record TextDelta(String text) implements StreamEvent {}
    record ThinkingDelta(String text) implements StreamEvent {}
    record ToolCallComplete(String toolUseId, String toolName,
                            java.util.Map<String, Object> arguments) implements StreamEvent {}
    record ToolCallParseError(String toolUseId, String toolName, String message) implements StreamEvent {}
    record StreamEnd(String stopReason) implements StreamEvent {}
    record Error(String message) implements StreamEvent {}
}
