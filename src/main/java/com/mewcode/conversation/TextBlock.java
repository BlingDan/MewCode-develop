package com.mewcode.conversation;

import java.util.Objects;

public record TextBlock(String text) implements ContentBlock {
    public TextBlock {
        text = Objects.requireNonNullElse(text, "");
    }
}
