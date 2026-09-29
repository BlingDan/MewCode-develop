package com.mewcode.llm;

import com.mewcode.config.ProviderConfig;

/**
 * 所有 provider 协议共用的流式边界。
 *
 * <p>实现负责把 Anthropic、OpenAI Chat Completions 以及兼容 base URL 的服务差异 归一化为 {@link StreamEvent}；Agent
 * Loop 不直接依赖 SDK 类型，只关心文本、工具调用、 用量和流结束事件。
 */
public interface LlmClient {

  /** 打开结构化请求流。 */
  CancellableLlmStream openStream(PromptRequest request);

  /** 根据已校验的 provider 配置创建协议适配器。 */
  static LlmClient create(ProviderConfig provider) {
    return switch (provider.getProtocol()) {
      case "anthropic" -> new AnthropicClient(provider);
      case "openai", "deepseek" -> new OpenAiClient(provider);
      default -> throw new IllegalArgumentException("Unsupported provider protocol");
    };
  }
}
