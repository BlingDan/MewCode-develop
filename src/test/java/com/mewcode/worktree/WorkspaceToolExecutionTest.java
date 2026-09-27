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
  void readEnterReadBatchUsesTheDirectoryAtEachBarrier() throws Exception {
    new GitRepositoryFixture(root);
    root = root.toRealPath();
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "child", new CancellationToken()).path();
    java.nio.file.Files.writeString(root.resolve("notes.txt"), "parent version");
    java.nio.file.Files.writeString(child.resolve("notes.txt"), "child version");
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    var registry = ToolRegistry.createDefault();
    registry.register(new com.mewcode.tool.impl.WorktreeTool(workspace));
    try (var tools = new ToolExecutor(registry, root, new FileStateCache())) {
      tools.configureWorkspace(workspace);
      var results =
          tools.executeBatch(
              java.util.List.of(
                  new ToolCall(
                      "before", "ReadFile", Map.of("path", root.resolve("notes.txt").toString())),
                  new ToolCall("enter", "Worktree", Map.of("action", "enter", "name", "child")),
                  new ToolCall(
                      "after", "ReadFile", Map.of("path", child.resolve("notes.txt").toString()))));
      assertTrue(
          results.get(0).result().content().contains("parent version"),
          results.get(0).result().content());
      assertFalse(results.get(1).result().isError(), results.get(1).result().content());
      assertTrue(results.get(2).result().content().contains("child version"));
      assertEquals(child, workspace.currentCwd());
    }
    manager.exit(workspace, false, new CancellationToken());
  }

  @Test
  void parentReadCannotAuthorizeChildEditAndSurvivesKeepExit() throws Exception {
    new GitRepositoryFixture(root);
    root = root.toRealPath();
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "child", new CancellationToken()).path();
    var cache = new FileStateCache();
    var workspace = new AgentWorkspace(root, "session", "main", cache, manager);
    try (var tools = new ToolExecutor(ToolRegistry.createDefault(), root, cache)) {
      tools.configureWorkspace(workspace);
      assertFalse(
          tools
              .executeSingle(
                  new ToolCall(
                      "parent-read",
                      "ReadFile",
                      Map.of("path", root.resolve("notes.txt").toString())))
              .result()
              .isError());
      manager.enter(workspace, "child");
      var edit =
          Map.<String, Object>of(
              "path",
              child.resolve("notes.txt").toString(),
              "old_string",
              "baseline",
              "new_string",
              "child-edit");
      assertTrue(
          tools.executeSingle(new ToolCall("child-unread", "EditFile", edit)).result().isError());
      assertEquals("baseline\n", java.nio.file.Files.readString(child.resolve("notes.txt")));
      assertFalse(
          tools
              .executeSingle(
                  new ToolCall(
                      "child-read",
                      "ReadFile",
                      Map.of("path", child.resolve("notes.txt").toString())))
              .result()
              .isError());
      assertFalse(
          tools.executeSingle(new ToolCall("child-edit", "EditFile", edit)).result().isError());
      manager.exit(workspace, false, new CancellationToken());
      var result =
          tools
              .executeSingle(
                  new ToolCall(
                      "parent-edit",
                      "EditFile",
                      Map.of(
                          "path",
                          root.resolve("notes.txt").toString(),
                          "old_string",
                          "baseline",
                          "new_string",
                          "parent-edit")))
              .result();
      assertFalse(result.isError(), result.content());
      assertEquals("parent-edit\n", java.nio.file.Files.readString(root.resolve("notes.txt")));
      assertEquals("child-edit\n", java.nio.file.Files.readString(child.resolve("notes.txt")));
    }
  }

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
