package com.mewcode.agent;

import com.mewcode.conversation.Message;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 一次 Provider 请求使用的动态提示快照。 */
public record PromptAdditions(
    String memoryIndex,
    Optional<Message> resumeReminder,
    String skillCatalog,
    String activeSkills,
    List<String> hookReminders,
    String agentCatalog) {

  public PromptAdditions {
    memoryIndex = Objects.requireNonNullElse(memoryIndex, "");
    resumeReminder = resumeReminder == null ? Optional.empty() : resumeReminder;
    skillCatalog = Objects.requireNonNullElse(skillCatalog, "");
    activeSkills = Objects.requireNonNullElse(activeSkills, "");
    hookReminders =
        hookReminders == null
            ? List.of()
            : hookReminders.stream()
                .filter(Objects::nonNull)
                .filter(text -> !text.isBlank())
                .toList();
    agentCatalog = Objects.requireNonNullElse(agentCatalog, "");
  }

  public static PromptAdditions empty() {
    return new PromptAdditions("", Optional.empty(), "", "", List.of(), "");
  }
}
