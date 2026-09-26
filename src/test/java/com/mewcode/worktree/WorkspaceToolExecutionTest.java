package com.mewcode.worktree;

import static org.junit.jupiter.api.Assertions.*;

import com.mewcode.agent.CancellationToken;
import com.mewcode.config.WorktreeConfig;
import com.mewcode.tool.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceToolExecutionTest {
  @TempDir Path root;

  @Test
  void validationAndExecutionKeepTheCapturedDirectoryAfterKeepExit() throws Exception {
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "child", new CancellationToken()).path();
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    manager.enter(workspace, "child");
    var validated = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var registry = new ToolRegistry();
    registry.register(
        new Tool() {
          public String name() {
            return "Capture";
          }

          public String description() {
            return "测试固定目录";
          }

          public ToolCategory category() {
            return ToolCategory.FILE;
          }

          public Map<String, Object> inputSchema() {
            return Map.of("type", "object");
          }

          public boolean isReadOnly() {
            return true;
          }

          public boolean isDestructive() {
            return false;
          }

          public boolean isConcurrencySafe(Map<String, Object> input) {
            return true;
          }

          public String validateInput(Map<String, Object> input) {
            return null;
          }

          public String validateInput(ToolExecutionContext context, Map<String, Object> input) {
            validated.countDown();
            try {
              if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试超时");
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
            }
            return context.projectRoot().equals(child) ? null : "校验目录已改变";
          }

          public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
            return ToolResult.success(context.projectRoot().toString());
          }
        });
    try (var tools = new ToolExecutor(registry, root, new FileStateCache());
        var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      tools.configureWorkspace(workspace);
      var future =
          threads.submit(() -> tools.executeSingle(new ToolCall("one", "Capture", Map.of())));
      try {
        assertTrue(validated.await(2, TimeUnit.SECONDS));
        manager.exit(workspace, false, new CancellationToken());
        assertThrows(
            WorktreeException.class, () -> manager.remove("child", new CancellationToken()));
      } finally {
        release.countDown();
      }
      var result = future.get(3, TimeUnit.SECONDS).result();
      assertFalse(result.isError(), result.content());
      assertEquals(child.toString(), result.content());
    }
    assertTrue(manager.remove("child", new CancellationToken()));
  }
}
