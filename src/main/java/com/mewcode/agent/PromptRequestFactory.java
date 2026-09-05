package com.mewcode.agent;

import com.mewcode.compact.ContextRequest;
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

  private final SystemPromptBundle systemPrompt;

  public PromptRequestFactory(SystemPromptBundle systemPrompt) {
    this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
  }

  /** 使用当前模式和轮次创建一次 provider 请求快照。 */
  public PromptRequest create(
      AgentMode mode,
      int round,
      boolean forceFull,
      List<Message> history,
      List<Map<String, Object>> tools) {
    return create(mode, round, forceFull, history, tools, List.of());
  }

  /** 创建请求并在本轮 Reminder 中列出尚未发现的延迟工具名。 */
  public PromptRequest create(
      AgentMode mode,
      int round,
      boolean forceFull,
      List<Message> history,
      List<Map<String, Object>> tools,
      List<String> deferredToolNames) {
    return create(
        mode, round, forceFull, history, tools, deferredToolNames, PromptAdditions.empty());
  }

  /** 创建请求并合并本轮 memory 和恢复提醒快照。 */
  public PromptRequest create(
      AgentMode mode,
      int round,
      boolean forceFull,
      List<Message> history,
      List<Map<String, Object>> tools,
      PromptAdditions additions) {
    return create(mode, round, forceFull, history, tools, List.of(), additions);
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
    PromptAdditions dynamic = additions == null ? PromptAdditions.empty() : additions;
    var segments = new java.util.ArrayList<>(systemPrompt.systemSegments());
    if (!dynamic.skillCatalog().isBlank()) segments.add(dynamic.skillCatalog());
    if (!dynamic.memoryIndex().isBlank()) {
      segments.add(
          "Long-term memory index (reference only; verify details when needed):\n"
              + dynamic.memoryIndex());
    }
    if (!dynamic.activeSkills().isBlank()) segments.add(dynamic.activeSkills());
    return new PromptRequest(
        segments,
        tools,
        history,
        mergeReminders(
            SystemReminderFactory.create(context, deferredToolNames), dynamic.resumeReminder()));
  }

  /** 创建上下文预检所需的 system、tools 和 reminder 快照，不携带 history。 */
  public ContextRequest createContextRequest(
      AgentMode mode,
      int round,
      boolean forceFull,
      List<Map<String, Object>> tools,
      List<String> deferredToolNames) {
    PromptRequest request = create(mode, round, forceFull, List.of(), tools, deferredToolNames);
    return new ContextRequest(request.systemSegments(), request.tools(), request.reminder());
  }

  /** 创建带动态 additions 的上下文预检请求。 */
  public ContextRequest createContextRequest(
      AgentMode mode,
      int round,
      boolean forceFull,
      List<Map<String, Object>> tools,
      List<String> deferredToolNames,
      PromptAdditions additions) {
    PromptRequest request =
        create(mode, round, forceFull, List.of(), tools, deferredToolNames, additions);
    return new ContextRequest(request.systemSegments(), request.tools(), request.reminder());
  }

  /** 返回会话级稳定 bundle；不会暴露可变内部集合。 */
  public SystemPromptBundle systemPrompt() {
    return systemPrompt;
  }

  private static java.util.Optional<Message> mergeReminders(
      java.util.Optional<Message> base, java.util.Optional<Message> extra) {
    if (extra == null || extra.isEmpty()) return base;
    if (base == null || base.isEmpty()) return extra;
    var blocks = new java.util.ArrayList<ContentBlock>();
    blocks.addAll(base.get().content());
    blocks.add(new TextBlock("\n"));
    blocks.addAll(extra.get().content());
    return java.util.Optional.of(new Message("user", blocks));
  }
}
