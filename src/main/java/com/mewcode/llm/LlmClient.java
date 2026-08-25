package com.mewcode.llm;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * 所有 provider 协议共用的流式边界。
 *
 * <p>实现负责把 Anthropic、OpenAI Chat Completions 以及兼容 base URL 的服务差异 归一化为 {@link StreamEvent}；Agent
 * Loop 不直接依赖 SDK 类型，只关心文本、工具调用、 用量和流结束事件。
 */
public interface LlmClient {

  /** 打开结构化请求流；默认适配到旧的会话/字符串入口，不回写真实会话。 */
  default CancellableLlmStream openStream(PromptRequest request) {
    if (request == null) throw new IllegalArgumentException("request must not be null");
    var compatibilityConversation = new ConversationManager();
    for (Message message : request.history()) compatibilityConversation.addMessage(message);
    request.reminder().ifPresent(compatibilityConversation::addMessage);
    return openStream(compatibilityConversation, request.tools(), request.flattenedSystemPrompt());
  }

  /** 打开基于普通消息列表的可取消流。 */
  default CancellableLlmStream openStream(
      List<Message> messages, List<Map<String, Object>> apiTools) {
    return new CancellableLlmStream(stream(messages, apiTools), () -> {});
  }

  /** 打开基于 provider 无关会话历史的可取消流。 */
  default CancellableLlmStream openStream(
      ConversationManager conversation, List<Map<String, Object>> apiTools) {
    return new CancellableLlmStream(stream(conversation, apiTools), () -> {});
  }

  /** 打开一轮请求并允许本轮覆盖系统提示词；旧实现默认忽略覆盖值以保持兼容。 */
  default CancellableLlmStream openStream(
      ConversationManager conversation, List<Map<String, Object>> apiTools, String systemPrompt) {
    return openStream(conversation, apiTools);
  }

  /** 兼容旧调用方，把消息列表复制到临时会话后启动流。 */
  default BlockingQueue<StreamEvent> stream(
      List<Message> messages, List<Map<String, Object>> apiTools) {
    var conversation = new ConversationManager();
    if (messages != null) {
      for (Message message : messages) conversation.addMessage(message);
    }
    return stream(conversation);
  }

  /** 兼容旧测试客户端，同时保留对真实客户端的工具定义传递。 */
  default BlockingQueue<StreamEvent> stream(
      ConversationManager conversation, List<Map<String, Object>> apiTools) {
    return stream(conversation);
  }

  /** 兼容最早的无工具流接口。 */
  default BlockingQueue<StreamEvent> stream(ConversationManager conversation) {
    return stream(conversation.getMessages(), List.of());
  }
}
