package com.mewcode.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mewcode.config.ProviderConfig;
import com.mewcode.llm.LlmClient;
import com.mewcode.testsupport.FakeLlmClient;
import com.mewcode.tool.ToolApiProtocol;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderRouterTest {

  @Test
  void selectsConfiguredProviderCachesClientAndFallsBackWhenMissing() {
    ProviderConfig mainConfig = provider("main", "anthropic");
    ProviderConfig reviewConfig = provider("reviewer", "openai");
    LlmClient mainClient = new FakeLlmClient();
    AtomicInteger creations = new AtomicInteger();
    ProviderRouter router =
        new ProviderRouter(
            List.of(mainConfig, reviewConfig),
            mainConfig,
            mainClient,
            (config, prompt) -> {
              creations.incrementAndGet();
              return new FakeLlmClient();
            },
            "system");

    assertSame(mainClient, router.select(null).client());
    assertSame(mainClient, router.select("missing").client());
    ProviderRouter.Route first = router.select("reviewer");
    ProviderRouter.Route second = router.select("reviewer");
    assertSame(first.client(), second.client());
    assertEquals(ToolApiProtocol.OPENAI, first.protocol());
    assertEquals(1, creations.get());
  }

  private static ProviderConfig provider(String name, String protocol) {
    ProviderConfig config = new ProviderConfig();
    config.setName(name);
    config.setProtocol(protocol);
    config.setModel(name + "-model");
    return config;
  }
}
