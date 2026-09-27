package com.mewcode.worktree;

import static org.junit.jupiter.api.Assertions.*;

import com.mewcode.agent.CancellationToken;
import com.mewcode.config.HookConfigLoader;
import com.mewcode.config.WorktreeConfig;
import com.mewcode.hook.*;
import com.mewcode.permission.*;
import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.support.CommandRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeHookLifecycleTest {
  @TempDir Path root;

  @Test
  void cancellationDoesNotReleaseAStillRunningHookAndItsCwdStaysFixed() throws Exception {
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "child", new CancellationToken()).path();
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    manager.enter(workspace, "child");
    var context = workspace.capture(new CancellationToken());
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    BashSandbox paused =
        new BashSandbox() {
          public boolean isAvailable() {
            return true;
          }

          public SandboxedProcess prepare(BashSandboxRequest request) throws java.io.IOException {
            started.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (release.getCount() != 0 && System.nanoTime() < deadline) {
              try {
                release.await(20, TimeUnit.MILLISECONDS);
              } catch (InterruptedException ignored) {
                /* 模拟尚未实际停止的运行体。 */
              }
            }
            if (release.getCount() != 0) throw new java.io.IOException("测试等待超时");
            return new SandboxedProcess(
                List.of("/bin/sh", "-c", request.command()), request.projectRoot());
          }
        };
    var rule =
        new HookRule(
            "paused",
            HookEvent.POST_TOOL_USE,
            Optional.empty(),
            new HookAction.Shell(
                "mkdir -p .mewcode/hook-events; printf hook > .mewcode/hook-events/output"),
            false,
            true,
            Duration.ofSeconds(5),
            root.resolve("hooks.yaml"));
    var state = new HookSessionState();
    try (var engine =
        new HookEngine(
            new HookConfigLoader.LoadedHooks(List.of(rule), List.of()),
            new CommandRunner(paused),
            ignored -> {})) {
      engine.dispatch(
          new HookInvocation(
              HookEvent.POST_TOOL_USE,
              Map.of("cwd", child.toString()),
              state,
              context.cancellationToken(),
              context));
      try {
        assertTrue(started.await(2, TimeUnit.SECONDS));
        manager.exit(workspace, false, new CancellationToken());
        engine.cancelSession(state);
        assertTrue(engine.hasPending(state));
        assertThrows(
            WorktreeException.class, () -> manager.remove("child", new CancellationToken()));
      } finally {
        release.countDown();
      }
      assertTrue(engine.awaitSessionIdle(state, Duration.ofSeconds(5)));
      assertEquals("hook", Files.readString(child.resolve(".mewcode/hook-events/output")));
      assertFalse(Files.exists(root.resolve(".mewcode/hook-events/output")));
    }
    // 实际 Hook 使用权已释放，可以重新进入；不透明命令仍须保留资源。
    manager.enter(workspace, "child");
    manager.exit(workspace, false, new CancellationToken());
    var failure =
        assertThrows(
            WorktreeException.class, () -> manager.remove("child", new CancellationToken()));
    assertTrue(failure.getMessage().contains("停止"), failure.getMessage());
    assertTrue(Files.isDirectory(child));
  }
}
