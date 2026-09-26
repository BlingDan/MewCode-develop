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

  @org.junit.jupiter.api.Test
  void runnerUsesExplicitDirectoryAndTreatsArgumentsLiterally() throws Exception {
    var repo = new GitRepositoryFixture(temporary.resolve("repo space"));
    var runner = new GitCommandRunner();
    assertEquals(
        repo.root.toString(),
        runner
            .run(
                repo.root,
                new com.mewcode.agent.CancellationToken(),
                "rev-parse",
                "--show-toplevel")
            .strip());
    assertThrows(
        WorktreeException.class,
        () ->
            runner.run(
                repo.root, new com.mewcode.agent.CancellationToken(), "show", ";touch injected"));
    assertFalse(java.nio.file.Files.exists(repo.root.resolve("injected")));
  }

  @org.junit.jupiter.api.Test
  void timeoutStopsTheStartedProcess() throws Exception {
    var process = new java.util.concurrent.atomic.AtomicReference<Process>();
    var runner =
        new GitCommandRunner(
            java.time.Duration.ofMillis(100),
            builder -> {
              var started = new ProcessBuilder("/bin/sh", "-c", "sleep 10").start();
              process.set(started);
              return started;
            });
    assertThrows(
        WorktreeException.class,
        () -> runner.run(temporary, new com.mewcode.agent.CancellationToken(), "status"));
    assertFalse(process.get().isAlive());
  }
}
