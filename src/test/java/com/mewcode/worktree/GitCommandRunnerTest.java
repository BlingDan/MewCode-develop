package com.mewcode.worktree;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitCommandRunnerTest {
  @TempDir Path temporary;

  @Test
  void fixtureCreatesCommittedFileAndKnownLocalRemote() throws Exception {
    var repo = new GitRepositoryFixture(temporary);
    assertEquals("baseline", repo.git("show", "HEAD:notes.txt"));
    assertEquals(repo.git("rev-parse", "HEAD"), repo.git("rev-parse", "refs/remotes/fixture/main"));
    assertEquals("", repo.git("status", "--porcelain"));
  }
}
