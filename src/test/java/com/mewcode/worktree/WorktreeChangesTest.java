package com.mewcode.worktree;

import static org.junit.jupiter.api.Assertions.*;

import com.mewcode.agent.CancellationToken;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeChangesTest {
  @TempDir Path root;

  @Test
  void countsDirtyUntrackedAndCommitsEvenWhenLocallyKnownAsPushed() throws Exception {
    var repo = new GitRepositoryFixture(root);
    String base = repo.git("rev-parse", "HEAD");
    var changes = new WorktreeChanges();
    var token = new CancellationToken();
    assertEquals(new WorktreeChanges.ChangeSummary(0, 0), changes.countChanges(root, base, token));
    assertFalse(changes.hasUnpushedCommits(root, token));
    Files.writeString(root.resolve("notes.txt"), "dirty");
    Files.writeString(root.resolve("new file\n.txt"), "new");
    assertEquals(2, changes.countChanges(root, base, token).changedFiles());
    repo.git("add", ".");
    repo.git("commit", "-m", "成果");
    repo.knownRemote();
    assertFalse(changes.hasUnpushedCommits(root, token));
    assertEquals(1, changes.countChanges(root, base, token).commits());
    assertTrue(changes.hasChanges(root, base, token));
    repo.git("update-ref", "-d", "refs/remotes/fixture/main");
    assertTrue(changes.hasUnpushedCommits(root, token));
  }

  @Test
  void failedOrCancelledDetectionRetainsResources() throws Exception {
    new GitRepositoryFixture(root);
    var token = new CancellationToken();
    token.cancel();
    var changes = new WorktreeChanges();
    assertTrue(changes.hasChanges(root, "bad", token));
    assertThrows(WorktreeException.class, () -> changes.countChanges(root, "bad", token));
  }
}
