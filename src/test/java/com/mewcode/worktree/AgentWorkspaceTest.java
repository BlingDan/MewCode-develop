package com.mewcode.worktree;

import static org.junit.jupiter.api.Assertions.*;

import com.mewcode.agent.CancellationToken;
import com.mewcode.config.WorktreeConfig;
import com.mewcode.tool.FileStateCache;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentWorkspaceTest {
  @TempDir Path root;

  @Test
  void keepExitPreservesOldSnapshotAndUsageUntilActualCompletion() throws Exception {
    var repo = new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    var child = manager.create(root, "child", new CancellationToken());
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    manager.enter(workspace, "child");
    var captured = workspace.capture(new CancellationToken());
    try (var use = captured.workspaceScope().retain()) {
      manager.exit(workspace, false, new CancellationToken());
      assertEquals(root.toRealPath(), workspace.currentCwd());
      assertEquals(child.path(), captured.projectRoot());
      assertThrows(WorktreeException.class, () -> manager.remove("child", new CancellationToken()));
    }
    assertTrue(manager.remove("child", new CancellationToken()));
    assertFalse(Files.exists(child.path()));
    assertEquals("baseline", repo.git("show", "HEAD:notes.txt"));
  }

  @Test
  void promptAndFileCacheAreIsolatedByAbsoluteDirectoryAndAgent() throws Exception {
    new GitRepositoryFixture(root);
    Files.writeString(root.resolve("MEWCODE.md"), "parent instruction");
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "child", new CancellationToken()).path();
    Files.writeString(child.resolve("MEWCODE.md"), "child instruction");
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    var factory = new com.mewcode.agent.PromptRequestFactory(workspace::systemPrompt);
    var beforeRequest =
        factory.create(
            com.mewcode.agent.AgentMode.EXECUTE,
            1,
            false,
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            com.mewcode.agent.PromptAdditions.empty());
    String old = workspace.systemPrompt().systemSegments().toString();
    manager.enter(workspace, "child");
    assertTrue(old.contains("parent instruction"));
    var afterRequest =
        factory.create(
            com.mewcode.agent.AgentMode.EXECUTE,
            2,
            false,
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            com.mewcode.agent.PromptAdditions.empty());
    assertTrue(String.join("\n", beforeRequest.systemSegments()).contains("parent instruction"));
    assertTrue(String.join("\n", afterRequest.systemSegments()).contains("child instruction"));
    assertTrue(workspace.systemPrompt().systemSegments().toString().contains("child instruction"));
    assertFalse(
        workspace.systemPrompt().systemSegments().toString().contains("parent instruction"));
    assertEquals(child, workspace.capture(new CancellationToken()).projectRoot());
    assertThrows(WorktreeException.class, () -> manager.enter(workspace, "child"));
    manager.exit(workspace, false, new CancellationToken());
  }

  @Test
  void projectMemoryInstancesRemainBoundToTheirAbsoluteDirectories() throws Exception {
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "memory-child", new CancellationToken()).path();
    var workspace =
        new AgentWorkspace(
            root, root.resolve("test-home"), "session", "main", new FileStateCache(), manager);
    var parentMemory = workspace.memory(false, ignored -> {});
    manager.enter(workspace, "memory-child");
    var childMemory = workspace.memory(false, ignored -> {});
    parentMemory.addManual("project_knowledge", "parent only");
    childMemory.addManual("project_knowledge", "child only");
    assertTrue(parentMemory.indexText().contains("parent only"));
    assertFalse(parentMemory.indexText().contains("child only"));
    assertTrue(childMemory.indexText().contains("child only"));
    manager.exit(workspace, false, new CancellationToken());
    assertSame(parentMemory, workspace.memory(false, ignored -> {}));
    workspace.closeMemories();
  }
}
