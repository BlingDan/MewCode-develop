package com.mewcode.llm;

import com.mewcode.config.ProviderConfig;

/** Creates the adapter selected by a validated provider configuration. */
public final class LlmClients {

    private LlmClients() {}

    public static LlmClient create(ProviderConfig provider, String systemPrompt) {
        return switch (provider.getProtocol()) {
            case "anthropic" -> new AnthropicClient(provider, systemPrompt);
            case "openai", "deepseek" -> new OpenAiClient(provider, systemPrompt);
            default -> throw new IllegalArgumentException("Unsupported provider protocol");
        };
    }
}
