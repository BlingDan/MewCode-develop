package com.mewcode.skill;

import com.mewcode.config.ProviderConfig;
import com.mewcode.llm.LlmClient;
import com.mewcode.tool.ToolApiProtocol;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/** 在一次 Agent 运行中按 Skill 偏好选择已配置 Provider。 */
public final class ProviderRouter {

  private final Map<String, ProviderConfig> configurations = new LinkedHashMap<>();
  private final Map<String, Route> routes = new LinkedHashMap<>();
  private final ProviderConfig mainConfig;
  private final Route main;
  private final BiFunction<ProviderConfig, String, LlmClient> clientFactory;
  private final String systemPrompt;

  public ProviderRouter(
      List<ProviderConfig> providers,
      ProviderConfig mainConfig,
      LlmClient mainClient,
      BiFunction<ProviderConfig, String, LlmClient> clientFactory,
      String systemPrompt) {
    if (providers != null) {
      for (ProviderConfig provider : providers) {
        if (provider != null && provider.getName() != null) {
          configurations.put(provider.getName(), provider);
        }
      }
    }
    this.mainConfig = Objects.requireNonNull(mainConfig, "mainConfig");
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    this.systemPrompt = Objects.requireNonNullElse(systemPrompt, "");
    this.main =
        new Route(
            mainConfig,
            Objects.requireNonNull(mainClient, "mainClient"),
            protocol(mainConfig),
            false);
    if (mainConfig.getName() != null) routes.put(mainConfig.getName(), main);
  }

  public Route main() {
    return main;
  }

  /** 未指定、未配置或创建失败时统一返回主流程 Provider。 */
  public synchronized Route select(String preferredName) {
    if (preferredName == null || preferredName.isBlank()) return main;
    ProviderConfig selected = configurations.get(preferredName);
    if (selected == null || selected == mainConfig) {
      return selected == null
          ? new Route(main.config(), main.client(), main.protocol(), true)
          : main;
    }
    Route cached = routes.get(preferredName);
    if (cached != null) return cached;
    try {
      Route created =
          new Route(
              selected, clientFactory.apply(selected, systemPrompt), protocol(selected), false);
      routes.put(preferredName, created);
      return created;
    } catch (RuntimeException error) {
      return new Route(main.config(), main.client(), main.protocol(), true);
    }
  }

  private static ToolApiProtocol protocol(ProviderConfig config) {
    return "anthropic".equalsIgnoreCase(config.getProtocol())
        ? ToolApiProtocol.ANTHROPIC
        : ToolApiProtocol.OPENAI;
  }

  public record Route(
      ProviderConfig config, LlmClient client, ToolApiProtocol protocol, boolean fallback) {}
}
