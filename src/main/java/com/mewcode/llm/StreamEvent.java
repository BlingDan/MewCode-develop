package com.mewcode.llm;

/** Provider 适配器向 Agent 层输出的统一流事件。 */
public sealed interface StreamEvent {
    /** 模型文本增量。 */
    record TextDelta(String text) implements StreamEvent {}
    /** 模型隐藏思考或签名增量，不直接显示在普通回答区域。 */
    record ThinkingDelta(String text, String signature) implements StreamEvent {
        public ThinkingDelta(String text) {
            this(text, "");
        }

        public ThinkingDelta {
            text = java.util.Objects.requireNonNullElse(text, "");
            signature = java.util.Objects.requireNonNullElse(signature, "");
        }
    }
    /** 本轮用量快照；缺失维度用 OptionalLong.empty 表示。 */
    record Usage(java.util.OptionalLong inputTokens,
                 java.util.OptionalLong cacheReadTokens,
                 java.util.OptionalLong cacheCreationTokens,
                 java.util.OptionalLong outputTokens) implements StreamEvent {
        /** 兼容旧 Provider，只提供 input/output 时 cache 维度视为缺失。 */
        public Usage(java.util.OptionalLong inputTokens,
                     java.util.OptionalLong outputTokens) {
            this(inputTokens, java.util.OptionalLong.empty(),
                    java.util.OptionalLong.empty(), outputTokens);
        }

        public Usage {
            java.util.Objects.requireNonNull(inputTokens, "inputTokens");
            java.util.Objects.requireNonNull(cacheReadTokens, "cacheReadTokens");
            java.util.Objects.requireNonNull(cacheCreationTokens, "cacheCreationTokens");
            java.util.Objects.requireNonNull(outputTokens, "outputTokens");
            if (hasNegative(inputTokens)
                    || hasNegative(cacheReadTokens)
                    || hasNegative(cacheCreationTokens)
                    || hasNegative(outputTokens)) {
                throw new IllegalArgumentException("Token counts must not be negative");
            }
        }

        private static boolean hasNegative(java.util.OptionalLong value) {
            return value.isPresent() && value.getAsLong() < 0;
        }
    }
    /** 参数 JSON 已完整解析的工具调用。 */
    record ToolCallComplete(String toolUseId, String toolName,
                            java.util.Map<String, Object> arguments) implements StreamEvent {}
    /** 工具参数无法解析，但仍保留调用 ID 供模型收到错误结果。 */
    record ToolCallParseError(String toolUseId, String toolName, String message) implements StreamEvent {}
    /** provider 已发出本轮结束原因。 */
    record StreamEnd(String stopReason) implements StreamEvent {}
    /** provider 请求失败或网络层异常。 */
    record Error(String message, ErrorKind errorKind) implements StreamEvent {
        public Error {
            message = java.util.Objects.requireNonNullElse(message, "Provider request failed.");
            errorKind = java.util.Objects.requireNonNull(errorKind, "errorKind");
        }

        /** 兼容旧调用方，默认按普通错误处理。 */
        public Error(String message) {
            this(message, ErrorKind.GENERAL);
        }
    }

    /** Provider 错误是否表示请求上下文超过硬限制。 */
    enum ErrorKind {
        GENERAL,
        CONTEXT_LENGTH
    }
}
