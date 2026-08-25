package com.mewcode.agent;

import com.mewcode.conversation.Message;
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
    var context = new ReminderContext(Objects.requireNonNull(mode, "mode"), round, forceFull);
    return new PromptRequest(
        systemPrompt.systemSegments(), tools, history, SystemReminderFactory.create(context));
  }

  /** 返回会话级稳定 bundle；不会暴露可变内部集合。 */
  public SystemPromptBundle systemPrompt() {
    return systemPrompt;
  }
}
