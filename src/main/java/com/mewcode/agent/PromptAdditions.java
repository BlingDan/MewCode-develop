package com.mewcode.agent;

import com.mewcode.conversation.Message;
import java.util.Objects;
import java.util.Optional;

/** 一次 Provider 请求使用的动态提示快照。 */
public record PromptAdditions(String memoryIndex, Optional<Message> resumeReminder) {

    public PromptAdditions {
        memoryIndex = Objects.requireNonNullElse(memoryIndex, "");
        resumeReminder = resumeReminder == null ? Optional.empty() : resumeReminder;
    }

    public static PromptAdditions empty() {
        return new PromptAdditions("", Optional.empty());
    }
}
