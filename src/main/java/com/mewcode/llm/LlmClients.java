package com.mewcode.llm;

import com.mewcode.config.ProviderConfig;

/** 根据已校验的 provider 配置创建协议适配器；deepseek 复用 OpenAI 兼容协议。 */
public final class LlmClients {

    private LlmClients() {}

    /** 将配置中的协议名映射为对应的流式客户端实现。 */
    public static LlmClient create(ProviderConfig provider, String systemPrompt) {
        return switch (provider.getProtocol()) {
            case "anthropic" -> new AnthropicClient(provider, systemPrompt);
            case "openai", "deepseek" -> new OpenAiClient(provider, systemPrompt);
            default -> throw new IllegalArgumentException("Unsupported provider protocol");
        };
    }
}
