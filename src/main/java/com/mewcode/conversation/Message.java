package com.mewcode.conversation;

import java.util.Objects;

/** A plain user or assistant message sent to an LLM. */
public record Message(String role, String content) {
    public Message {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        if (role.isBlank()) throw new IllegalArgumentException("role must not be blank");
    }
}
