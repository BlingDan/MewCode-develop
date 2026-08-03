package com.mewcode.llm;

/** Protocol-neutral events emitted by a streaming LLM request. */
public sealed interface StreamEvent {
    record TextDelta(String text) implements StreamEvent {}
    record ThinkingDelta(String text) implements StreamEvent {}
    record StreamEnd(String stopReason) implements StreamEvent {}
    record Error(String message) implements StreamEvent {}
}
