package com.mewcode.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.command.CommandRegistry;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.PromptRequest;
import com.mewcode.llm.StreamEvent;
import com.mewcode.prompt.PromptBuilder;
import com.mewcode.skill.ProviderRouter;
import com.mewcode.skill.SkillCatalog;
import com.mewcode.skill.SkillDefinition;
import com.mewcode.skill.SkillRun;
import com.mewcode.testsupport.FakeLlmClient;
import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolExecutor;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import com.mewcode.tool.impl.LoadSkillTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentTurnCoordinatorSkillTest {

  @TempDir Path temp;

  @Test
  void loadsFullSopOnDemandAndClearsItAfterTheRequest() throws Exception {
    SkillCatalog catalog = catalog("shared", null);
    var client = new FakeLlmClient();
    client.enqueue(
        new StreamEvent.ToolCallComplete(
            "load", "LoadSkill", Map.of("name", "custom", "arguments", "raw args")),
        new StreamEvent.StreamEnd("tool_use"));
    client.enqueue(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("end_turn"));
    client.enqueue(new StreamEvent.TextDelta("plain"), new StreamEvent.StreamEnd("end_turn"));
    var registry = new ToolRegistry();
    registry.register(new LoadSkillTool());
    registry.register(new EchoTool());
    var conversation = new ConversationManager();

    try (var executor =
        new ToolExecutor(
            registry,
            new ToolExecutionContext(temp, Duration.ofSeconds(2), new FileStateCache()))) {
      var coordinator =
          new AgentTurnCoordinator(
              client,
              registry,
              executor,
              conversation,
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(),
              new PromptRequestFactory(PromptBuilder.buildBundle(temp)));
      coordinator.configureSkills(
          catalog,
          () -> catalog.refresh(registry.ordinaryToolNames(), Set.of()),
          null,
          null);

      await(coordinator.startRun("use it", AgentMode.EXECUTE));
      await(coordinator.startRun("plain request", AgentMode.EXECUTE));
    }

    List<PromptRequest> requests = client.requests();
    assertTrue(requests.get(0).flattenedSystemPrompt().contains("custom: custom description"));
    assertFalse(requests.get(0).flattenedSystemPrompt().contains("FULL custom SOP"));
    assertTrue(requests.get(1).systemSegments().getLast().contains("FULL custom SOP raw args"));
    assertFalse(requests.get(2).flattenedSystemPrompt().contains("FULL custom SOP"));
    assertEquals(Set.of("LoadSkill", "Echo"), toolNames(requests.get(1)));
  }

  @Test
  void fallsBackOnceToMainProviderWithoutCommittingPartialText() throws Exception {
    SkillCatalog catalog = catalog("shared", "reviewer");
    var main = new FakeLlmClient();
    main.enqueue(new StreamEvent.TextDelta("main answer"), new StreamEvent.StreamEnd("end_turn"));
    var reviewer = new FakeLlmClient();
    reviewer.enqueue(new StreamEvent.TextDelta("partial"), new StreamEvent.Error("failed"));
    ProviderConfig mainConfig = provider("main", "openai");
    ProviderConfig reviewerConfig = provider("reviewer", "openai");
    ProviderRouter router =
        new ProviderRouter(
            List.of(mainConfig, reviewerConfig),
            mainConfig,
            main,
            (config, prompt) -> reviewer,
            "system");
    var registry = new ToolRegistry();
    registry.register(new LoadSkillTool());
    registry.register(new EchoTool());
    var conversation = new ConversationManager();
    SkillRun skills = new SkillRun();
    skills.activate(catalog.find("custom").orElseThrow(), "");

    List<AgentEvent> events;
    try (var executor =
        new ToolExecutor(
            registry,
            new ToolExecutionContext(temp, Duration.ofSeconds(2), new FileStateCache()))) {
      var coordinator =
          new AgentTurnCoordinator(
              main,
              registry,
              executor,
              conversation,
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(),
              new PromptRequestFactory(PromptBuilder.buildBundle(temp)));
      coordinator.configureSkills(
          catalog,
          () -> catalog.refresh(registry.ordinaryToolNames(), CommandRegistry.createDefault().reservedNames()),
          router,
          null);
      events = await(coordinator.startRun("review", AgentMode.EXECUTE, skills));
    }

    assertTrue(events.stream().anyMatch(AgentEvent.ProviderFallback.class::isInstance));
    assertEquals("main answer", conversation.getMessages().getLast().textContent());
    assertFalse(conversation.getMessages().getLast().textContent().contains("partial"));
    assertEquals(1, reviewer.requestCount());
    assertEquals(1, main.requestCount());
  }

  private SkillCatalog catalog(String mode, String model) throws Exception {
    Path project = temp.resolve("project-" + (model == null ? "default" : model));
    Path home = temp.resolve("home-" + (model == null ? "default" : model));
    Path skills = project.resolve(".mewcode/skills");
    Files.createDirectories(skills);
    Files.writeString(
        skills.resolve("custom.md"),
        "---\nname: custom\ndescription: custom description\ntools: [Echo]\nmode: "
            + mode
            + (model == null ? "" : "\nmodel: " + model)
            + "\n---\nFULL custom SOP {{arguments}}\n");
    SkillCatalog catalog = SkillCatalog.load(project, home);
    catalog.refresh(Set.of("LoadSkill", "Echo", "ReadFile", "Bash", "Grep", "Glob"), Set.of());
    return catalog;
  }

  private static ProviderConfig provider(String name, String protocol) {
    ProviderConfig config = new ProviderConfig();
    config.setName(name);
    config.setProtocol(protocol);
    config.setModel(name);
    return config;
  }

  private static Set<String> toolNames(PromptRequest request) {
    var names = new java.util.LinkedHashSet<String>();
    for (Map<String, Object> tool : request.tools()) {
      Object function = tool.get("function");
      if (function instanceof Map<?, ?> map) names.add((String) map.get("name"));
      else names.add((String) tool.get("name"));
    }
    return Set.copyOf(names);
  }

  private static List<AgentEvent> await(AgentRun run) throws Exception {
    var events = new ArrayList<AgentEvent>();
    while (true) {
      AgentEvent event = run.events().poll(3, TimeUnit.SECONDS);
      if (event == null) throw new AssertionError("agent timeout");
      events.add(event);
      if (event instanceof AgentEvent.LoopComplete) return events;
    }
  }

  private static final class EchoTool implements Tool {
    public String name() { return "Echo"; }
    public String description() { return "echo"; }
    public ToolCategory category() { return ToolCategory.SEARCH; }
    public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) { return ToolResult.success("ok"); }
    public boolean isReadOnly() { return true; }
    public boolean isDestructive() { return false; }
    public boolean isConcurrencySafe(Map<String, Object> input) { return true; }
    public String validateInput(Map<String, Object> input) { return null; }
  }
}
