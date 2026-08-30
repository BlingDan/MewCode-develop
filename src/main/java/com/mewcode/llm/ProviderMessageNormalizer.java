package com.mewcode.llm;

import com.mewcode.conversation.ContentBlock;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ToolResultBlock;
import java.util.ArrayList;
import java.util.List;

/** 仅在 provider 出站序列化前合并可兼容的相邻同角色消息。 */
final class ProviderMessageNormalizer {

  private ProviderMessageNormalizer() {}

  static List<Message> normalize(List<Message> messages) {
    if (messages == null || messages.isEmpty()) return List.of();
    var normalized = new ArrayList<Message>(messages.size());
    for (Message message : messages) {
      if (!normalized.isEmpty()) {
        Message previous = normalized.getLast();
        if (canMerge(previous, message)) {
          var blocks = new ArrayList<ContentBlock>(previous.content());
          blocks.addAll(message.content());
          normalized.set(normalized.size() - 1, new Message(previous.role(), blocks));
          continue;
        }
      }
      normalized.add(message);
    }
    return List.copyOf(normalized);
  }

  private static boolean canMerge(Message previous, Message current) {
    if (!previous.role().equals(current.role())) return false;
    // tool 结果必须紧跟原 assistant tool call，不能跨普通 user 文本合并。
    return !containsToolResult(previous) && !containsToolResult(current);
  }

  private static boolean containsToolResult(Message message) {
    return message.content().stream().anyMatch(ToolResultBlock.class::isInstance);
  }
}
