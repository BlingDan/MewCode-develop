package com.mewcode.conversation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ToolUseBlock(
        String toolUseId,
        String toolName,
        Map<String, Object> arguments) implements ContentBlock {

    public ToolUseBlock {
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(toolName, "toolName");
        arguments = arguments == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(arguments));
    }
}
