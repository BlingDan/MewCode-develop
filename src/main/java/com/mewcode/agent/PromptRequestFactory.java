package com.mewcode.agent;

import com.mewcode.conversation.ContentBlock;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.TextBlock;
import com.mewcode.llm.PromptRequest;
import com.mewcode.prompt.ReminderContext;
import com.mewcode.prompt.SystemPromptBundle;
import com.mewcode.prompt.SystemReminderFactory;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 在 Agent 层组装稳定提示、历史快照、工具定义和本轮 Reminder。 */
public final class PromptRequestFactory {

  private final List<String> fixedSystemSegments;
  private final java.util.function.Supplier<SystemPromptBundle> promptSupplier;

  public PromptRequestFactory(java.util.function.Supplier<SystemPromptBundle> promptSupplier) {
    this(promptSupplier, null);
  }

  private PromptRequestFactory(
      java.util.function.Supplier<SystemPromptBundle> promptSupplier,
      List<String> fixedSystemSegments) {
    this.promptSupplier = Objects.requireNonNull(promptSupplier, "promptSupplier");
    Objects.requireNonNull(promptSupplier.get(), "systemPrompt");
    this.fixedSystemSegments =
        fixedSystemSegments == null ? null : List.copyOf(fixedSystemSegments);
  }

  /** 返回使用固定 system 前缀的新工厂，供 Fork 复用父本轮实际请求。 */
  public PromptRequestFactory withFixedSystemSegments(List<String> systemSegments) {
    return new PromptRequestFactory(promptSupplier, systemSegments);
  }

  /** 返回当前工作区的稳定提示词快照。 */
  public SystemPromptBundle systemPrompt() {
    return promptSupplier.get();
  }

  /** 创建请求并在本轮 Reminder 中列出延迟工具和恢复提示。 */
  public PromptRequest create(
      AgentMode mode,
      int round,
      boolean forceFull,
      List<Message> history,
      List<Map<String, Object>> tools,
      List<String> deferredToolNames,
      PromptAdditions additions) {
    var context = new ReminderContext(Objects.requireNonNull(mode, "mode"), round, forceFull);
    PromptAdditions dynamic = Objects.requireNonNull(additions, "additions");
    var segments =
        new java.util.ArrayList<>(
            fixedSystemSegments == null
                ? promptSupplier.get().systemSegments()
                : fixedSystemSegments);
    if (!dynamic.skillCatalog().isBlank()) segments.add(dynamic.skillCatalog());
    if (!dynamic.agentCatalog().isBlank()) segments.add(dynamic.agentCatalog());
    if (!dynamic.memoryIndex().isBlank()) {
      segments.add(
          "Long-term memory index (reference only; verify details when needed):\n"
              + dynamic.memoryIndex());
    }
    if (!dynamic.activeSkills().isBlank()) segments.add(dynamic.activeSkills());
    var reminder =
        mergeReminders(
            SystemReminderFactory.create(context, deferredToolNames), dynamic.resumeReminder());
    reminder = mergeReminders(reminder, hookReminder(dynamic.hookReminders()));
    return new PromptRequest(segments, tools, history, reminder);
  }

  private static java.util.Optional<Message> mergeReminders(
      java.util.Optional<Message> base, java.util.Optional<Message> extra) {
    if (extra.isEmpty()) return base;
    if (base.isEmpty()) return extra;
    var blocks = new java.util.ArrayList<ContentBlock>();
    blocks.addAll(base.get().content());
    blocks.add(new TextBlock("\n"));
    blocks.addAll(extra.get().content());
    return java.util.Optional.of(new Message("user", blocks));
  }

  private static java.util.Optional<Message> hookReminder(List<String> texts) {
    if (texts.isEmpty()) return java.util.Optional.empty();
    var blocks = new java.util.ArrayList<ContentBlock>();
    for (int index = 0; index < texts.size(); index++) {
      if (index > 0) blocks.add(new TextBlock("\n"));
      blocks.add(new TextBlock(texts.get(index)));
    }
    return java.util.Optional.of(new Message("user", blocks));
  }
}
