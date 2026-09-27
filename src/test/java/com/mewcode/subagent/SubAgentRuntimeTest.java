package com.mewcode.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.agent.AgentEvent;
import com.mewcode.agent.AgentLoopConfig;
import com.mewcode.agent.AgentMode;
import com.mewcode.agent.AgentRun;
import com.mewcode.agent.AgentTurnCoordinator;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.StreamEvent;
import com.mewcode.permission.BashSandboxFactory;
import com.mewcode.permission.PathAuthorizationStore;
import com.mewcode.permission.PermissionGate;
import com.mewcode.permission.PermissionRuleEngine;
import com.mewcode.prompt.PromptBuilder;
import com.mewcode.skill.ProviderRouter;
import com.mewcode.testsupport.FakeLlmClient;
import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolExecutor;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.impl.AgentTool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubAgentRuntimeTest {

  @TempDir Path projectRoot;

  @Test
  void definitionUsesCleanContextAndReturnsFinalTextToParent() throws Exception {
    var client = new FakeLlmClient();
    client.enqueue(
        new StreamEvent.ToolCallComplete(
            "agent-1",
            AgentTool.NAME,
            Map.of(
                "prompt", "inspect files",
                "description", "inspect",
                "subagent_type", "explore")),
        new StreamEvent.StreamEnd("tool_use"));
    client.enqueue(
        new StreamEvent.TextDelta("child result"), new StreamEvent.StreamEnd("end_turn"));
    client.enqueue(
        new StreamEvent.TextDelta("parent result"), new StreamEvent.StreamEnd("end_turn"));

    var registry = ToolRegistry.createDefault();
    registry.register(new AgentTool());
    var provider = provider("main", "model-one");
    var router =
        new ProviderRouter(
            List.of(provider), provider, client, (ignored, prompt) -> client, "system");
    var promptFactory =
        new com.mewcode.agent.PromptRequestFactory(PromptBuilder.buildBundle(projectRoot));
    var permissionGate = new PermissionGate();
    var permissionRules = new PermissionRuleEngine();

    try (var tasks = new SubAgentTaskManager();
        var executor = new ToolExecutor(registry, projectRoot, new FileStateCache())) {
      var coordinator =
          new AgentTurnCoordinator(
              client,
              registry,
              executor,
              new ConversationManager(),
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(5, 3),
              promptFactory);
      coordinator.setSubAgentRuntime(
          new SubAgentRuntime(
              AgentCatalog.load(projectRoot, projectRoot, List.of(), 5),
              tasks,
              registry,
              projectRoot,
              promptFactory,
              new AgentLoopConfig(5, 3),
              permissionGate,
              permissionRules,
              new PathAuthorizationStore(projectRoot),
              BashSandboxFactory.create(),
              null,
              "session",
              1_000,
              router));

      AgentRun run = coordinator.startRun("delegate", AgentMode.EXECUTE);
      List<AgentEvent> events = collect(run);

      assertTrue(
          events.stream()
              .anyMatch(
                  event ->
                      event instanceof AgentEvent.ToolResult result
                          && result.result().contains("child result")));
      assertEquals(3, client.requestCount());
      assertEquals(
          List.of(new com.mewcode.conversation.Message("user", "inspect files")),
          client.requests().get(1).history());
      assertTrue(client.requests().get(1).flattenedSystemPrompt().contains("只读取和搜索项目"));
      assertTrue(client.requests().get(1).tools().toString().contains("ReadFile"));
      assertTrue(
          client.requests().get(1).tools().stream()
              .noneMatch(tool -> tool.toString().contains("Agent")));
      assertTrue(client.requests().get(2).history().toString().contains("child result"));
    }
  }

  @Test
  void isolatedRoleReadsCommittedChildCopyAndCleansUpBeforeReturning() throws Exception {
    var repo = new com.mewcode.worktree.GitRepositoryFixture(projectRoot);
    java.nio.file.Files.createDirectories(projectRoot.resolve(".mewcode/agents"));
    java.nio.file.Files.writeString(
        projectRoot.resolve(".mewcode/agents/isolated.md"),
        "---\nname: isolated\ndescription: isolated reader\nisolation: worktree\npermissionMode: dontAsk\ntools: [Bash]\n---\nRead notes.txt and return its contents.");
    repo.git("add", ".mewcode/agents");
    repo.git("commit", "-m", "role");
    repo.knownRemote();
    java.nio.file.Files.writeString(projectRoot.resolve("notes.txt"), "parent changed");
    var client = new FakeLlmClient();
    client.enqueue(
        new StreamEvent.ToolCallComplete("read", "Bash", Map.of("command", "cat notes.txt")),
        new StreamEvent.StreamEnd("tool_use"));
    client.enqueue(
        new StreamEvent.TextDelta("child baseline"), new StreamEvent.StreamEnd("end_turn"));
    var registry = ToolRegistry.createDefault();
    var provider = provider("main", "model");
    var router =
        new ProviderRouter(
            List.of(provider), provider, client, (ignored, prompt) -> client, "system");
    var manager =
        new com.mewcode.worktree.WorktreeManager(
            projectRoot, new com.mewcode.config.WorktreeConfig(), "session");
    var workspace =
        new com.mewcode.worktree.AgentWorkspace(
            projectRoot,
            projectRoot.resolve("test-home"),
            "session",
            "main",
            new FileStateCache(),
            manager);
    var promptFactory = new com.mewcode.agent.PromptRequestFactory(workspace);
    try (var tasks = new SubAgentTaskManager()) {
      var runtime =
          new SubAgentRuntime(
              AgentCatalog.load(projectRoot, projectRoot, List.of(), 5),
              tasks,
              registry,
              projectRoot,
              promptFactory,
              new AgentLoopConfig(5, 3),
              new PermissionGate(),
              new PermissionRuleEngine(),
              new PathAuthorizationStore(projectRoot),
              BashSandboxFactory.create(),
              null,
              "session",
              10_000,
              router);
      runtime.configureWorkspace(workspace);
      var parent =
          new SubAgentRuntime.ParentAgentSnapshot(
              promptFactory.create(AgentMode.EXECUTE, 1, false, List.of(), List.of()),
              List.of(),
              com.mewcode.agent.ToolPolicy.forMode(AgentMode.EXECUTE),
              router.main(),
              AgentMode.EXECUTE,
              new AgentRun(),
              "session",
              workspace.capture(new com.mewcode.agent.CancellationToken()));
      var result =
          runtime.execute(
              new SubAgentRuntime.SubAgentInvocation(
                  "read notes", "read", "isolated", null, false, "dispatch"),
              parent);
      assertTrue(!result.isError(), result.content());
      assertTrue(result.content().contains("无成果工作树已清理"), result.content());
      assertTrue(client.requests().get(1).history().toString().contains("baseline"));
      assertTrue(!client.requests().get(1).history().toString().contains("parent changed"));
      assertTrue(client.requests().getFirst().flattenedSystemPrompt().contains("重新读取"));
      assertTrue(manager.list().isEmpty());
      assertEquals(
          "parent changed", java.nio.file.Files.readString(projectRoot.resolve("notes.txt")));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void dirtyChildIsRetainedAfterSuccessOrProviderFailure(boolean fails) throws Exception {
    var repo = new com.mewcode.worktree.GitRepositoryFixture(projectRoot);
    java.nio.file.Files.createDirectories(projectRoot.resolve(".mewcode/agents"));
    java.nio.file.Files.writeString(
        projectRoot.resolve(".mewcode/agents/editor.md"),
        "---\nname: editor\ndescription: editor\nisolation: worktree\npermissionMode: dontAsk\ntools: [Bash]\n---\nEdit notes.");
    repo.git("add", ".mewcode/agents");
    repo.git("commit", "-m", "role");
    repo.knownRemote();
    var client = new FakeLlmClient();
    client.enqueue(
        new StreamEvent.ToolCallComplete(
            "write", "Bash", Map.of("command", "printf 'child result' > notes.txt")),
        new StreamEvent.StreamEnd("tool_use"));
    if (fails) client.enqueue(new StreamEvent.Error("provider test failure"));
    else
      client.enqueue(
          new StreamEvent.TextDelta("done editing"), new StreamEvent.StreamEnd("end_turn"));
    var registry = ToolRegistry.createDefault();
    var provider = provider("main", "model");
    var router =
        new ProviderRouter(
            List.of(provider), provider, client, (ignored, prompt) -> client, "system");
    var manager =
        new com.mewcode.worktree.WorktreeManager(
            projectRoot, new com.mewcode.config.WorktreeConfig(), "session");
    var workspace =
        new com.mewcode.worktree.AgentWorkspace(
            projectRoot,
            projectRoot.resolve("test-home"),
            "session",
            "main",
            new FileStateCache(),
            manager);
    var promptFactory = new com.mewcode.agent.PromptRequestFactory(workspace);
    try (var tasks = new SubAgentTaskManager()) {
      var runtime =
          new SubAgentRuntime(
              AgentCatalog.load(projectRoot, projectRoot, List.of(), 5),
              tasks,
              registry,
              projectRoot,
              promptFactory,
              new AgentLoopConfig(5, 3),
              new PermissionGate(),
              new PermissionRuleEngine(),
              new PathAuthorizationStore(projectRoot),
              BashSandboxFactory.create(),
              null,
              "session",
              10_000,
              router);
      runtime.configureWorkspace(workspace);
      var parent =
          new SubAgentRuntime.ParentAgentSnapshot(
              promptFactory.create(AgentMode.EXECUTE, 1, false, List.of(), List.of()),
              List.of(),
              com.mewcode.agent.ToolPolicy.forMode(AgentMode.EXECUTE),
              router.main(),
              AgentMode.EXECUTE,
              new AgentRun(),
              "session",
              workspace.capture(new com.mewcode.agent.CancellationToken()));
      var result =
          runtime.execute(
              new SubAgentRuntime.SubAgentInvocation(
                  "edit", "edit", "editor", null, false, "dispatch"),
              parent);
      assertEquals(fails, result.isError(), result.content());
      assertTrue(result.content().contains("工作树已保留"), result.content());
      var child = manager.list().getFirst();
      assertEquals(
          "child result", java.nio.file.Files.readString(child.path().resolve("notes.txt")));
      assertEquals("baseline\n", java.nio.file.Files.readString(projectRoot.resolve("notes.txt")));
    }
  }

  private static List<AgentEvent> collect(AgentRun run) throws Exception {
    var events = new ArrayList<AgentEvent>();
    while (true) {
      AgentEvent event = run.events().next();
      events.add(event);
      if (event instanceof AgentEvent.LoopComplete) return events;
    }
  }

  private static ProviderConfig provider(String name, String model) {
    var provider = new ProviderConfig();
    provider.setName(name);
    provider.setProtocol("openai");
    provider.setModel(model);
    provider.setApiKey("test");
    return provider;
  }
}
