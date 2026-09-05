package com.mewcode.agent;

import com.mewcode.conversation.Message;
import java.util.Objects;
import java.util.Optional;

/** 一次 Provider 请求使用的动态提示快照。 */
public record PromptAdditions(
        String memoryIndex,
        Optional<Message> resumeReminder,
        String skillCatalog,
        String activeSkills) {

    public PromptAdditions {
        memoryIndex = Objects.requireNonNullElse(memoryIndex, "");
        resumeReminder = resumeReminder == null ? Optional.empty() : resumeReminder;
        skillCatalog = Objects.requireNonNullElse(skillCatalog, "");
        activeSkills = Objects.requireNonNullElse(activeSkills, "");
    }

    public PromptAdditions(String memoryIndex, Optional<Message> resumeReminder) {
        this(memoryIndex, resumeReminder, "", "");
    }

    public static PromptAdditions empty() {
        return new PromptAdditions("", Optional.empty(), "", "");
    }
}
