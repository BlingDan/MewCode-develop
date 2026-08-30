package com.mewcode.config;

/** config.yaml 中的一条 LLM provider 配置；{@code apiKey} 只用于建连，不参与日志展示。 */
public final class ProviderConfig {

    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 128_000;

    private String name;
    private String protocol;
    private String model;
    private String baseUrl;
    private String apiKey;
    private boolean thinking;
    private int contextWindowTokens = DEFAULT_CONTEXT_WINDOW_TOKENS;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public boolean isThinking() { return thinking; }
    public void setThinking(boolean thinking) { this.thinking = thinking; }

    public int getContextWindowTokens() {
        return contextWindowTokens > 0 ? contextWindowTokens : DEFAULT_CONTEXT_WINDOW_TOKENS;
    }
    public void setContextWindowTokens(int contextWindowTokens) {
        this.contextWindowTokens = contextWindowTokens;
    }

    @Override
    public String toString() {
        return "ProviderConfig{" +
                "name='" + name + '\'' +
                ", protocol='" + protocol + '\'' +
                ", model='" + model + '\'' +
                ", baseUrl='" + baseUrl + '\'' +
                ", apiKey='[REDACTED]'" +
                ", thinking=" + thinking +
                '}';
    }
}
