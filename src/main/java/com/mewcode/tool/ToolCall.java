package com.mewcode.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** provider 无关的完整工具调用。 */
public record ToolCall(String toolUseId, String toolName, Map<String, Object> arguments) {
    public ToolCall {
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(toolName, "toolName");
        arguments = arguments == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(arguments));
    }
}
