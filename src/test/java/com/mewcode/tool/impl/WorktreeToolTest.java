package com.mewcode.tool.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.mewcode.agent.CancellationToken;
import com.mewcode.config.WorktreeConfig;
import com.mewcode.tool.*;
import com.mewcode.worktree.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeToolTest {
  @TempDir Path root;

  @Test
  void createsSeparatelyThenEntersAndKeepsThenDeletes() throws Exception {
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    var registry = ToolRegistry.createDefault();
    registry.register(new WorktreeTool(workspace));
    try (var executor = new ToolExecutor(registry, root, new FileStateCache())) {
      executor.configureWorkspace(workspace);
      assertFalse(call(executor, "create", "manual").isError());
      assertEquals(root.toRealPath(), workspace.currentCwd());
      assertFalse(call(executor, "list", null).isError());
      assertFalse(call(executor, "enter", "manual").isError());
      assertTrue(workspace.currentSession().isPresent());
      assertFalse(call(executor, "exit", null).isError());
      assertEquals(root.toRealPath(), workspace.currentCwd());
      assertTrue(Files.exists(manager.list().getFirst().path()));
      assertFalse(call(executor, "delete", "manual").isError());
      assertTrue(manager.list().isEmpty());
    }
  }

  @Test
  void rejectsModelDiscardIntentAndInvalidParameters() throws Exception {
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    var tool = new WorktreeTool(workspace);
    manager.create(root, "dirty", new CancellationToken());
    Path child = manager.list().getFirst().path();
    Files.writeString(child.resolve("notes.txt"), "result");
    assertTrue(
        tool.execute(
                workspace.capture(new CancellationToken()),
                Map.of("action", "delete", "name", "dirty", "discardChanges", true))
            .isError());
    assertEquals("result", Files.readString(child.resolve("notes.txt")));
    assertNotNull(tool.validateInput(Map.of("action", "create", "name", "../escape")));
    assertNotNull(tool.validateInput(Map.of("action", "list", "cwd", root.toString())));
    assertNotNull(tool.validateInput(Map.of("action", "exit", "discardChanges", true)));
    assertFalse(tool.isSystem());
    assertFalse(tool.isConcurrencySafe(Map.of("action", "list")));
  }

  @Test
  void trustedDiscardCannotBeReusedAfterTheSameNameIsRecreated() throws Exception {
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    var tool = new WorktreeTool(workspace);
    var registry = new ToolRegistry();
    registry.register(tool);
    manager.create(root, "same", new CancellationToken());
    var call =
        new ToolCall(
            "trusted",
            "Worktree",
            Map.of("action", "delete", "name", "same", "discardChanges", true));
    try (var capability =
            tool.authorizeUserDiscard(
                call.arguments(), manager.resourceIdentityForUserCommand("same"));
        var executor = new ToolExecutor(registry, root, new FileStateCache())) {
      executor.configureWorkspace(workspace);
      assertFalse(executor.executeSingle(call).result().isError());
      manager.create(root, "same", new CancellationToken());
      Path child = manager.list().getFirst().path();
      Files.writeString(child.resolve("notes.txt"), "new result");
      assertTrue(executor.executeSingle(call).result().isError());
      assertEquals("new result", Files.readString(child.resolve("notes.txt")));
    }
  }

  private ToolResult call(ToolExecutor executor, String action, String name) {
    Map<String, Object> args =
        name == null ? Map.of("action", action) : Map.of("action", action, "name", name);
    return executor
        .executeSingle(new ToolCall(java.util.UUID.randomUUID().toString(), "Worktree", args))
        .result();
  }
}
