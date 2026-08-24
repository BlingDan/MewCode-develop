package com.mewcode.tool;

import java.util.Objects;

/** 保存调用 ID 和结果，避免并发执行完成顺序影响模型看到的顺序。 */
public record ToolInvocationResult(String toolUseId, ToolResult result) {
    public ToolInvocationResult {
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(result, "result");
    }
}
