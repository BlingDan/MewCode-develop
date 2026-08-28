package com.mewcode.prompt;

import com.mewcode.conversation.Message;
import com.mewcode.conversation.TextBlock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 生成只在本轮 provider 请求中存在的 System Reminder user 消息。 */
public final class SystemReminderFactory {

  private static final String OPEN_TAG = "<system-reminder>\n";
  private static final String CLOSE_TAG = "\n</system-reminder>";

  private SystemReminderFactory() {}

  /** 按四轮周期选择完整或精简提醒。首轮及每四轮的起始轮为完整提醒。 */
  public static Optional<Message> create(ReminderContext context) {
    return create(context, List.of());
  }

  /** 按四轮周期选择提醒，并追加当前尚未发现的延迟工具名称。 */
  public static Optional<Message> create(ReminderContext context, List<String> deferredToolNames) {
    Objects.requireNonNull(context, "context");
    List<String> names = deferredToolNames == null ? List.of() : List.copyOf(deferredToolNames);
    boolean full = context.forceFull() || context.round() == 1 || context.round() % 4 == 1;
    return Optional.of(full ? full(context, names) : compact(context, names));
  }

  /** 生成包含轮次状态和完整行为约束的提醒。 */
  public static Message full(ReminderContext context) {
    return full(context, List.of());
  }

  /** 生成完整提醒，并列出尚未发现的延迟工具名称。 */
  public static Message full(ReminderContext context, List<String> deferredToolNames) {
    Objects.requireNonNull(context, "context");
    String mode = modeName(context);
    String content =
        "Current mode: "
            + mode
            + "\nAgent Loop round: "
            + context.round()
            + "\nThis is a full reminder. Follow the active mode restrictions for this round.\n"
            + modeConstraint(context)
            + "\nUse dedicated tools when available, read a target before editing it, and adjust tool parameters after errors instead of claiming success.\n"
            + "Continue across tool rounds when the task requires more investigation or verification."
            + deferredTools(deferredToolNames);
    return fromContent(content);
  }

  /** 生成只保留模式和关键约束的精简提醒。 */
  public static Message compact(ReminderContext context) {
    return compact(context, List.of());
  }

  /** 生成精简提醒，并列出尚未发现的延迟工具名称。 */
  public static Message compact(ReminderContext context, List<String> deferredToolNames) {
    Objects.requireNonNull(context, "context");
    String content =
        "Current mode: "
            + modeName(context)
            + "\n"
            + modeConstraint(context)
            + " Keep the current task moving and respect tool restrictions."
            + deferredTools(deferredToolNames);
    return fromContent(content);
  }

  /** 将任意补充文本封装为 provider 无关的合成 user 消息。 */
  public static Message fromContent(String content) {
    String escaped = escapeXmlText(Objects.requireNonNullElse(content, ""));
    return new Message("user", List.of(new TextBlock(OPEN_TAG + escaped + CLOSE_TAG)));
  }

  private static String modeName(ReminderContext context) {
    return context.mode() == com.mewcode.agent.AgentMode.PLAN ? "PLAN" : "EXECUTE";
  }

  private static String modeConstraint(ReminderContext context) {
    return context.mode() == com.mewcode.agent.AgentMode.PLAN
        ? "In PLAN mode, use only safe read-only tools, do not modify files, and finish with an ordered plan."
        : "In EXECUTE mode, complete the requested change with the supplied tools and verify important results.";
  }

  private static String deferredTools(List<String> names) {
    if (names == null || names.isEmpty()) return "";
    return "\n尚未向模型展开完整定义的 MCP 工具（需要时先调用 ToolSearch）：\n"
        + names.stream()
            .map(name -> "- " + Objects.requireNonNullElse(name, ""))
            .reduce((a, b) -> a + "\n" + b)
            .orElse("");
  }

  private static String escapeXmlText(String content) {
    return content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
