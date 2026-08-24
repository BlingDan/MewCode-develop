package com.mewcode.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/** Agent Loop 对 UI 暴露的 provider 无关事件。 */
public sealed interface AgentEvent
        permits AgentEvent.StreamText, AgentEvent.ToolUse, AgentEvent.ToolResult,
        AgentEvent.TurnComplete, AgentEvent.LoopComplete, AgentEvent.Usage,
        AgentEvent.Error {

    /** 模型文本增量。 */
    record StreamText(String text) implements AgentEvent {
        public StreamText {
            text = Objects.requireNonNullElse(text, "");
        }
    }

    /** 模型请求调用工具。 */
    record ToolUse(String requestId,
                   String toolName,
                   Map<String, Object> input) implements AgentEvent {
        public ToolUse {
            requestId = requireText(requestId, "requestId");
            toolName = requireText(toolName, "toolName");
            input = input == null
                    ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(input));
        }
    }

    /** 工具执行完成；result 是完整的模型可见结果文本。 */
    record ToolResult(String requestId,
                      String toolName,
                      String result,
                      boolean isError,
                      long durationMillis) implements AgentEvent {
        public ToolResult {
            requestId = requireText(requestId, "requestId");
            toolName = requireText(toolName, "toolName");
            result = Objects.requireNonNullElse(result, "");
            if (durationMillis < 0) {
                throw new IllegalArgumentException("durationMillis must not be negative");
            }
        }
    }

    /** 一轮 LLM 请求完整结束。 */
    record TurnComplete(int round) implements AgentEvent {
        public TurnComplete {
            if (round <= 0) throw new IllegalArgumentException("round must be positive");
        }
    }

    /** 整个 Agent Loop 完成。 */
    record LoopComplete(int totalRounds) implements AgentEvent {
        public LoopComplete {
            if (totalRounds < 0) {
                throw new IllegalArgumentException("totalRounds must not be negative");
            }
        }
    }

    /** 整个 Loop 累计的 Token 用量；OptionalLong.empty() 表示 provider 未提供。 */
    record Usage(OptionalLong inputTokens,
                 OptionalLong outputTokens) implements AgentEvent {
        public Usage {
            inputTokens = Objects.requireNonNull(inputTokens, "inputTokens");
            outputTokens = Objects.requireNonNull(outputTokens, "outputTokens");
            if (inputTokens.isPresent() && inputTokens.getAsLong() < 0
                    || outputTokens.isPresent() && outputTokens.getAsLong() < 0) {
                throw new IllegalArgumentException("Token counts must not be negative");
            }
        }
    }

    /** 不可恢复的 provider、工具或 Loop 错误。用户取消本身不使用此事件表示。 */
    record Error(String message, ErrorCategory category) implements AgentEvent {
        public Error {
            message = requireText(message, "message");
            category = Objects.requireNonNull(category, "category");
        }

        public Error(String message) {
            this(message, ErrorCategory.LOOP);
        }
    }

    enum ErrorCategory {
        PROVIDER,
        TOOL,
        LOOP
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
