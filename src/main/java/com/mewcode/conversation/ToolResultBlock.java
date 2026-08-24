package com.mewcode.conversation;

import java.util.Objects;

public record ToolResultBlock(
        String toolUseId,
        String content,
        boolean isError) implements ContentBlock {

    public ToolResultBlock {
        Objects.requireNonNull(toolUseId, "toolUseId");
        content = Objects.requireNonNullElse(content, "");
    }
}
