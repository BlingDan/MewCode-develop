package com.mewcode.worktree;

import static org.junit.jupiter.api.Assertions.*;

import com.mewcode.agent.CancellationToken;
import com.mewcode.config.HookConfigLoader;
import com.mewcode.config.WorktreeConfig;
import com.mewcode.hook.*;
import com.mewcode.permission.*;
import com.mewcode.tool.*;
import com.mewcode.tool.support.CommandRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeCurrentDeletionTest {
  @TempDir Path root;

  @Test
  void postHookObservesPreparedStateBeforeActualDeleteAndRunsOnlyOnce() throws Exception {
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "child", new CancellationToken()).path();
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    manager.enter(workspace, "child");
    var registry = new ToolRegistry();
    registry.register(exitTool(manager, workspace));
    var calls = new AtomicInteger();
    BashSandbox recording =
        new BashSandbox() {
          public boolean isAvailable() {
            return true;
          }

          public SandboxedProcess prepare(BashSandboxRequest request) throws java.io.IOException {
            assertTrue(Files.isDirectory(child));
            assertEquals(child, request.projectRoot());
            calls.incrementAndGet();
            return new MacSeatbeltSandbox().prepare(request);
          }
        };
    var rule =
        new HookRule(
            "post",
            HookEvent.POST_TOOL_USE,
            Optional.empty(),
            new HookAction.Shell(
                "mkdir -p .mewcode/hook-events; cat > .mewcode/hook-events/post; grep -q prepared .mewcode/hook-events/post"),
            false,
            false,
            Duration.ofSeconds(3),
            root.resolve("hooks.yaml"));
    var errors = new java.util.ArrayList<String>();
    try (var engine =
            new HookEngine(
                new HookConfigLoader.LoadedHooks(List.of(rule), List.of()),
                new CommandRunner(recording),
                errors::add);
        var executor = new ToolExecutor(registry, root, new FileStateCache())) {
      executor.configureWorkspace(workspace);
      executor.configureHooks(engine, new HookSessionState());
      var result = executor.executeSingle(new ToolCall("exit", "Exit", Map.of())).result();
      assertFalse(result.isError(), result.content());
      assertEquals("success", result.metadata().get("status"));
      assertEquals(root.toRealPath(), workspace.currentCwd());
      assertFalse(Files.exists(child));
      assertEquals(1, calls.get());
      assertTrue(errors.isEmpty(), errors.toString());
    }
  }

  @Test
  void unfinishedAsyncPostHookRefusesCommitAndPreservesCurrentSession() throws Exception {
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "child", new CancellationToken()).path();
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    manager.enter(workspace, "child");
    var registry = new ToolRegistry();
    registry.register(exitTool(manager, workspace));
    var release = new java.util.concurrent.CountDownLatch(1);
    var state = new HookSessionState();
    BashSandbox paused =
        new BashSandbox() {
          public boolean isAvailable() {
            return true;
          }

          public SandboxedProcess prepare(BashSandboxRequest request) throws java.io.IOException {
            try {
              if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                throw new java.io.IOException("测试等待超时");
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              throw new java.io.IOException("测试中断");
            }
            return new SandboxedProcess(
                List.of("/bin/sh", "-c", request.command()), request.projectRoot());
          }
        };
    var rule =
        new HookRule(
            "async",
            HookEvent.POST_TOOL_USE,
            Optional.empty(),
            new HookAction.Shell("true"),
            false,
            true,
            Duration.ofSeconds(3),
            root.resolve("hooks.yaml"));
    try (var engine =
            new HookEngine(
                new HookConfigLoader.LoadedHooks(List.of(rule), List.of()),
                new CommandRunner(paused),
                ignored -> {});
        var executor = new ToolExecutor(registry, root, new FileStateCache())) {
      executor.configureWorkspace(workspace);
      executor.configureHooks(engine, state);
      try {
        var result = executor.executeSingle(new ToolCall("exit", "Exit", Map.of())).result();
        assertTrue(result.isError());
        assertTrue(result.content().contains("Post Hook"), result.content());
        assertTrue(workspace.currentSession().isPresent());
        assertEquals(child, workspace.currentCwd());
        assertTrue(Files.isDirectory(child));
      } finally {
        release.countDown();
      }
      assertTrue(engine.awaitSessionIdle(state, Duration.ofSeconds(5)));
    }
    manager.exit(workspace, false, new CancellationToken());
    assertTrue(manager.remove("child", new CancellationToken()));
  }

  private Tool exitTool(WorktreeManager manager, AgentWorkspace workspace) {
    return new Tool() {
      public String name() {
        return "Exit";
      }

      public String description() {
        return "退出测试工作树";
      }

      public ToolCategory category() {
        return ToolCategory.SHELL;
      }

      public Map<String, Object> inputSchema() {
        return Map.of("type", "object");
      }

      public boolean isReadOnly() {
        return false;
      }

      public boolean isDestructive() {
        return true;
      }

      public boolean isConcurrencySafe(Map<String, Object> input) {
        return false;
      }

      public String validateInput(Map<String, Object> input) {
        return null;
      }

      public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
        var prepared = manager.prepareExit(workspace, true, context.cancellationToken());
        return ToolResult.success("prepared：已准备，尚未提交")
            .withMetadata(Map.of("status", "prepared", WorktreeManager.FINALIZATION_KEY, prepared));
      }
    };
  }
}
