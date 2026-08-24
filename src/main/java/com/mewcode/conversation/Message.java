package com.mewcode.conversation;

import java.util.List;
import java.util.Objects;

/** 可承载文本、工具调用和工具结果的 provider 无关消息。 */
public record Message(String role, List<ContentBlock> content) {
    public Message {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        if (role.isBlank()) throw new IllegalArgumentException("role must not be blank");
        content = List.copyOf(content);
    }

    public Message(String role, String text) {
        this(role, List.of(new TextBlock(text)));
    }

    public String textContent() {
        var result = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof TextBlock text) result.append(text.text());
        }
        return result.toString();
    }
}
