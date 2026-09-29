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
import com.mewcode.permission.BashSandbox;
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
    var router = new ProviderRouter(List.of(provider), provider, client, ignored -> client);
    var promptFactory =
        new com.mewcode.agent.PromptRequestFactory(() -> PromptBuilder.buildBundle(projectRoot));
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
              promptFactory,
              null,
              null,
              null,
              null,
              null);
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
              BashSandbox.create(),
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
      assertTrue(client.requests().get(1).systemSegments().toString().contains("只读取和搜索项目"));
      assertTrue(client.requests().get(1).tools().toString().contains("ReadFile"));
      assertTrue(
          client.requests().get(1).tools().stream()
              .noneMatch(tool -> tool.toString().contains("Agent")));
      assertTrue(client.requests().get(2).history().toString().contains("child result"));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "clean child: {0}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"success", "failure", "cancel"})
  void isolatedRoleReadsCommittedChildCopyAndCleansUpBeforeReturning(String terminal)
      throws Exception {
    boolean cancelled = terminal.equals("cancel");
    var readComplete = new java.util.concurrent.CountDownLatch(1);
    var repo = new com.mewcode.worktree.GitRepositoryFixture(projectRoot);
    java.nio.file.Files.createDirectories(projectRoot.resolve(".mewcode/agents"));
    java.nio.file.Files.writeString(
        projectRoot.resolve(".mewcode/agents/isolated.md"),
        "---\nname: isolated\ndescription: isolated reader\nisolation: worktree\npermissionMode: dontAsk\ntools: [ReadFile]\n---\nRead notes.txt and return its contents.");
    repo.git("add", ".mewcode/agents");
    repo.git("commit", "-m", "role");
    repo.knownRemote();
    java.nio.file.Files.writeString(projectRoot.resolve("notes.txt"), "parent changed");
    var client = new FakeLlmClient();
    var registry = ToolRegistry.createDefault();
    var provider = provider("main", "model");
    var manager =
        new com.mewcode.worktree.WorktreeManager(
            projectRoot, new com.mewcode.config.WorktreeConfig(), "session");
    var readerClient =
        new com.mewcode.llm.LlmClient() {
          @Override
          public com.mewcode.llm.CancellableLlmStream openStream(
              com.mewcode.llm.PromptRequest request) {
            if (client.requestCount() == 0)
              client.enqueue(
                  new StreamEvent.ToolCallComplete(
                      "read",
                      "ReadFile",
                      Map.of(
                          "path",
                          manager.list().getFirst().path().resolve("notes.txt").toString())),
                  new StreamEvent.StreamEnd("tool_use"));
            else {
              if (terminal.equals("failure"))
                client.enqueue(new StreamEvent.Error("测试 Provider 失败"));
              else if (cancelled) client.enqueue();
              else
                client.enqueue(
                    new StreamEvent.TextDelta("child baseline"),
                    new StreamEvent.StreamEnd("end_turn"));
              readComplete.countDown();
            }
            return client.openStream(request);
          }
        };
    var router =
        new ProviderRouter(List.of(provider), provider, readerClient, ignored -> readerClient);
    var workspace =
        new com.mewcode.worktree.AgentWorkspace(
            projectRoot,
            projectRoot.resolve("test-home"),
            "session",
            "main",
            new FileStateCache(),
            manager);
    var promptFactory = new com.mewcode.agent.PromptRequestFactory(workspace::systemPrompt);
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
              BashSandbox.create(),
              null,
              "session",
              10_000,
              router);
      runtime.configureWorkspace(workspace);
      var parent =
          new SubAgentRuntime.ParentAgentSnapshot(
              promptFactory.create(
                  AgentMode.EXECUTE,
                  1,
                  false,
                  List.of(),
                  List.of(),
                  List.of(),
                  com.mewcode.agent.PromptAdditions.empty()),
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
                  "read notes", "read", "isolated", null, cancelled, "dispatch"),
              parent);
      if (cancelled) {
        assertTrue(readComplete.await(5, java.util.concurrent.TimeUnit.SECONDS));
        String id = tasks.list("session").getFirst().taskId();
        assertTrue(tasks.cancel("session", id));
        var snapshot = awaitTerminal(tasks, id);
        assertEquals(SubAgentTaskManager.Status.CANCELLED, snapshot.status());
        result = com.mewcode.tool.ToolResult.error(snapshot.error());
      }
      assertEquals(!terminal.equals("success"), result.isError(), result.content());
      assertTrue(result.content().contains("无成果工作树已清理"), result.content());
      assertTrue(client.requests().get(1).history().toString().contains("baseline"));
      assertTrue(!client.requests().get(1).history().toString().contains("parent changed"));
      assertTrue(client.requests().getFirst().systemSegments().toString().contains("重新读取"));
      assertTrue(manager.list().isEmpty());
      assertEquals(
          "parent changed", java.nio.file.Files.readString(projectRoot.resolve("notes.txt")));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "dirty child: {0}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"success", "failure", "cancel"})
  void dirtyChildIsRetainedAfterSuccessFailureOrCancellation(String terminal) throws Exception {
    boolean fails = terminal.equals("failure");
    boolean cancelled = terminal.equals("cancel");
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
            "write",
            "Bash",
            Map.of(
                "command",
                "printf 'child result' > notes.txt" + (cancelled ? "; exec sleep 10" : ""))),
        new StreamEvent.StreamEnd("tool_use"));
    if (fails) client.enqueue(new StreamEvent.Error("provider test failure"));
    else if (!cancelled)
      client.enqueue(
          new StreamEvent.TextDelta("done editing"), new StreamEvent.StreamEnd("end_turn"));
    var registry = ToolRegistry.createDefault();
    var provider = provider("main", "model");
    var router = new ProviderRouter(List.of(provider), provider, client, ignored -> client);
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
    var promptFactory = new com.mewcode.agent.PromptRequestFactory(workspace::systemPrompt);
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
              BashSandbox.create(),
              null,
              "session",
              10_000,
              router);
      runtime.configureWorkspace(workspace);
      var parent =
          new SubAgentRuntime.ParentAgentSnapshot(
              promptFactory.create(
                  AgentMode.EXECUTE,
                  1,
                  false,
                  List.of(),
                  List.of(),
                  List.of(),
                  com.mewcode.agent.PromptAdditions.empty()),
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
                  "edit", "edit", "editor", null, cancelled, "dispatch"),
              parent);
      if (cancelled) {
        String id = tasks.list("session").getFirst().taskId();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
          var copies = manager.list();
          if (!copies.isEmpty()
              && java.nio.file.Files.readString(copies.getFirst().path().resolve("notes.txt"))
                  .equals("child result")) break;
          Thread.sleep(10);
        }
        assertEquals(
            "child result",
            java.nio.file.Files.readString(manager.list().getFirst().path().resolve("notes.txt")));
        assertTrue(tasks.cancel("session", id));
        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (tasks.get("session", id).orElseThrow().status()
                != SubAgentTaskManager.Status.CANCELLED
            && System.nanoTime() < deadline) Thread.sleep(10);
        var snapshot = tasks.get("session", id).orElseThrow();
        assertEquals(SubAgentTaskManager.Status.CANCELLED, snapshot.status());
        result = com.mewcode.tool.ToolResult.error(snapshot.error());
      }
      assertEquals(fails || cancelled, result.isError(), result.content());
      assertTrue(result.content().contains("工作树已保留"), result.content());
      var child = manager.list().getFirst();
      assertEquals(
          "child result", java.nio.file.Files.readString(child.path().resolve("notes.txt")));
      assertEquals("baseline\n", java.nio.file.Files.readString(projectRoot.resolve("notes.txt")));
    }
  }

  @Test
  void twoIsolatedRolesRunConcurrentlyAndEditDifferentCopiesOfTheSameFile() throws Exception {
    var repo = new com.mewcode.worktree.GitRepositoryFixture(projectRoot);
    java.nio.file.Files.createDirectories(projectRoot.resolve(".mewcode/agents"));
    for (String role : List.of("one", "two"))
      java.nio.file.Files.writeString(
          projectRoot.resolve(".mewcode/agents/" + role + ".md"),
          "---\nname: "
              + role
              + "\ndescription: isolated editor\nisolation: worktree\npermissionMode: dontAsk\ntools: [ReadFile, EditFile]\n---\nROLE_"
              + role.toUpperCase(java.util.Locale.ROOT));
    repo.git("add", ".mewcode/agents");
    repo.git("commit", "-m", "两个隔离角色");
    repo.knownRemote();
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
    var promptFactory = new com.mewcode.agent.PromptRequestFactory(workspace::systemPrompt);
    var opened = new java.util.concurrent.CountDownLatch(2);
    var release = new java.util.concurrent.CountDownLatch(1);
    var stages =
        new java.util.concurrent.ConcurrentHashMap<
            Path, java.util.concurrent.atomic.AtomicInteger>();
    var roles = new java.util.concurrent.ConcurrentHashMap<Path, String>();
    var client =
        new com.mewcode.llm.LlmClient() {
          @Override
          public com.mewcode.llm.CancellableLlmStream openStream(
              com.mewcode.llm.PromptRequest request) {
            String system = request.systemSegments().toString();
            Path cwd =
                manager.list().stream()
                    .map(r -> r.path())
                    .filter(p -> system.contains(p.toString()))
                    .findFirst()
                    .orElseThrow();
            String role = system.contains("ROLE_ONE") ? "one" : "two";
            roles.put(cwd, role);
            int stage =
                stages
                    .computeIfAbsent(
                        cwd, ignored -> new java.util.concurrent.atomic.AtomicInteger())
                    .getAndIncrement();
            if (stage == 0) {
              opened.countDown();
              try {
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                  throw new AssertionError("并行派发超时");
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
              }
            }
            var response = new FakeLlmClient();
            if (stage == 0)
              response.enqueue(
                  new StreamEvent.ToolCallComplete(
                      "read", "ReadFile", Map.of("path", cwd.resolve("notes.txt").toString())),
                  new StreamEvent.StreamEnd("tool_use"));
            else if (stage == 1)
              response.enqueue(
                  new StreamEvent.ToolCallComplete(
                      "edit",
                      "EditFile",
                      Map.of(
                          "path",
                          cwd.resolve("notes.txt").toString(),
                          "old_string",
                          "baseline",
                          "new_string",
                          role + "-result")),
                  new StreamEvent.StreamEnd("tool_use"));
            else
              response.enqueue(
                  new StreamEvent.TextDelta(role + " done"), new StreamEvent.StreamEnd("end_turn"));
            return response.openStream(request);
          }
        };
    var provider = provider("main", "model");
    var router = new ProviderRouter(List.of(provider), provider, client, ignored -> client);
    try (var tasks = new SubAgentTaskManager()) {
      var runtime =
          new SubAgentRuntime(
              AgentCatalog.load(projectRoot, projectRoot, List.of(), 5),
              tasks,
              ToolRegistry.createDefault(),
              projectRoot,
              promptFactory,
              new AgentLoopConfig(5, 3),
              new PermissionGate(),
              new PermissionRuleEngine(),
              new PathAuthorizationStore(projectRoot),
              BashSandbox.create(),
              null,
              "session",
              10_000,
              router);
      runtime.configureWorkspace(workspace);
      var parent =
          new SubAgentRuntime.ParentAgentSnapshot(
              promptFactory.create(
                  AgentMode.EXECUTE,
                  1,
                  false,
                  List.of(),
                  List.of(),
                  List.of(),
                  com.mewcode.agent.PromptAdditions.empty()),
              List.of(),
              com.mewcode.agent.ToolPolicy.forMode(AgentMode.EXECUTE),
              router.main(),
              AgentMode.EXECUTE,
              new AgentRun(),
              "session",
              workspace.capture(new com.mewcode.agent.CancellationToken()));
      try {
        for (String role : List.of("one", "two")) {
          var launched =
              runtime.execute(
                  new SubAgentRuntime.SubAgentInvocation(
                      "edit notes", role, role, null, true, "dispatch-" + role),
                  parent);
          assertTrue(!launched.isError(), launched.content());
        }
        assertTrue(opened.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(2, roles.size());
        assertEquals(java.util.Set.of("one", "two"), new java.util.HashSet<>(roles.values()));
        assertEquals(2, manager.list().stream().map(r -> r.branch()).distinct().count());
        assertTrue(tasks.list("session").stream().allMatch(t -> t.endedAt() == null));
      } finally {
        release.countDown();
      }
      for (var task : tasks.list("session")) {
        var finished = awaitTerminal(tasks, task.taskId());
        assertEquals(SubAgentTaskManager.Status.COMPLETED, finished.status(), finished.error());
        assertTrue(finished.result().contains("工作树已保留"), finished.result());
      }
      for (var entry : roles.entrySet())
        assertEquals(
            entry.getValue() + "-result\n",
            java.nio.file.Files.readString(entry.getKey().resolve("notes.txt")));
      assertEquals("baseline\n", java.nio.file.Files.readString(projectRoot.resolve("notes.txt")));
      assertEquals(projectRoot.toRealPath(), workspace.currentCwd());
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

  private static SubAgentTaskManager.TaskSnapshot awaitTerminal(
      SubAgentTaskManager tasks, String id) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      var snapshot = tasks.get("session", id).orElseThrow();
      if (snapshot.endedAt() != null) return snapshot;
      Thread.sleep(10);
    }
    throw new AssertionError("子任务收尾超时：" + id);
  }

  @Test
  void failedIsolationInitializationNeverCallsProviderOrTouchesParentAndHidesConfig()
      throws Exception {
    var repo = new com.mewcode.worktree.GitRepositoryFixture(projectRoot);
    java.nio.file.Files.createDirectories(projectRoot.resolve(".mewcode/agents"));
    java.nio.file.Files.writeString(
        projectRoot.resolve(".mewcode/agents/broken.md"),
        "---\nname: broken\ndescription: broken\nisolation: worktree\n---\nEdit notes.");
    repo.git("add", ".mewcode/agents");
    repo.git("commit", "-m", "role");
    String credential = "fake-startup-config-secret";
    java.nio.file.Files.writeString(
        projectRoot.resolve(".mewcode/config.yaml"), "api_key: " + credential);
    var config = new com.mewcode.config.WorktreeConfig();
    config.setRequiredFiles(List.of("missing-runtime-file"));
    var manager = new com.mewcode.worktree.WorktreeManager(projectRoot, config, "session");
    var workspace =
        new com.mewcode.worktree.AgentWorkspace(
            projectRoot,
            projectRoot.resolve("test-home"),
            "session",
            "main",
            new FileStateCache(),
            manager);
    var promptFactory = new com.mewcode.agent.PromptRequestFactory(workspace::systemPrompt);
    var client = new FakeLlmClient();
    var provider = provider("main", "model");
    var router = new ProviderRouter(List.of(provider), provider, client, ignored -> client);
    try (var tasks = new SubAgentTaskManager()) {
      var runtime =
          new SubAgentRuntime(
              AgentCatalog.load(projectRoot, projectRoot, List.of(), 5),
              tasks,
              ToolRegistry.createDefault(),
              projectRoot,
              promptFactory,
              new AgentLoopConfig(5, 3),
              new PermissionGate(),
              new PermissionRuleEngine(),
              new PathAuthorizationStore(projectRoot),
              BashSandbox.create(),
              null,
              "session",
              10_000,
              router);
      runtime.configureWorkspace(workspace);
      var parent =
          new SubAgentRuntime.ParentAgentSnapshot(
              promptFactory.create(
                  AgentMode.EXECUTE,
                  1,
                  false,
                  List.of(),
                  List.of(),
                  List.of(),
                  com.mewcode.agent.PromptAdditions.empty()),
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
                  "edit", "edit", "broken", null, false, "dispatch"),
              parent);
      assertTrue(result.isError(), result.content());
      assertTrue(result.content().contains("初始化"), result.content());
      assertTrue(!result.content().contains(credential));
      assertEquals(0, client.requestCount());
      assertEquals("baseline\n", java.nio.file.Files.readString(projectRoot.resolve("notes.txt")));
      assertTrue(manager.list().isEmpty());
      assertTrue(tasks.drainNotifications("session").toString().indexOf(credential) < 0);
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
