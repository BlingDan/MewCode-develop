package com.mewcode.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 工具执行结果；metadata 只在本地使用，不发送给模型。 */
public record ToolResult(String content, boolean isError, Map<String, Object> metadata) {

    public ToolResult {
        content = Objects.requireNonNullElse(content, "");
        metadata = metadata == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public static ToolResult success(String content) {
        return new ToolResult(content, false, Map.of());
    }

    public static ToolResult error(String content) {
        return new ToolResult(content, true, Map.of());
    }

    public ToolResult withMetadata(Map<String, Object> extra) {
        var merged = new LinkedHashMap<>(metadata);
        if (extra != null) merged.putAll(extra);
        return new ToolResult(content, isError, merged);
    }
}
