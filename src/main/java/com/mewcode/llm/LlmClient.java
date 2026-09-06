package com.mewcode.llm;

/**
 * 所有 provider 协议共用的流式边界。
 *
 * <p>实现负责把 Anthropic、OpenAI Chat Completions 以及兼容 base URL 的服务差异 归一化为 {@link StreamEvent}；Agent
 * Loop 不直接依赖 SDK 类型，只关心文本、工具调用、 用量和流结束事件。
 */
public interface LlmClient {

  /** 打开一次结构化、可取消的 Provider 请求流。 */
  CancellableLlmStream openStream(PromptRequest request);
}
