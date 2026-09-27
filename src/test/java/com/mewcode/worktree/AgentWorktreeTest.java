package com.mewcode.worktree;

import static org.junit.jupiter.api.Assertions.*;

import com.mewcode.agent.CancellationToken;
import com.mewcode.config.WorktreeConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentWorktreeTest {
  @TempDir Path root;

  @Test
  void freezesBaselineAndProtectsChildChangesWithoutChangingParent() throws Exception {
    var repo = new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    var adapter = new AgentWorktree(manager, "agent-test", root.resolve("home"), "session");
    String frozen = manager.freezeHead(root, new CancellationToken());
    Files.writeString(root.resolve("new.txt"), "new parent commit");
    repo.git("add", "new.txt");
    repo.git("commit", "-m", "move parent after dispatch");
    Files.writeString(root.resolve("notes.txt"), "parent uncommitted");
    var result = adapter.create(root, "agent-test", frozen, new CancellationToken());
    assertEquals(frozen, result.headCommit());
    assertEquals("baseline\n", Files.readString(result.worktreePath().resolve("notes.txt")));
    assertTrue(adapter.buildNotice(root, result.worktreePath()).contains("重新读取"));
    assertTrue(adapter.workspace().currentSession().isEmpty());
    Files.writeString(result.worktreePath().resolve("notes.txt"), "child");
    adapter.release();
    assertThrows(WorktreeException.class, () -> adapter.remove(result, new CancellationToken()));
    assertEquals("parent uncommitted", Files.readString(root.resolve("notes.txt")));
    assertEquals("child", Files.readString(result.worktreePath().resolve("notes.txt")));
  }

  @Test
  void removesOnlyAnIdleChildWithKnownRemoteAndNoResults() throws Exception {
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    var adapter = new AgentWorktree(manager, "reader", root.resolve("home"), "session");
    var result =
        adapter.create(
            root,
            "reader",
            manager.freezeHead(root, new CancellationToken()),
            new CancellationToken());
    assertThrows(WorktreeException.class, () -> adapter.remove(result, new CancellationToken()));
    adapter.release();
    assertTrue(adapter.remove(result, new CancellationToken()));
    assertFalse(Files.exists(result.worktreePath()));
  }
}
