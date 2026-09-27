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

  @Test
  void cancellationStopsAnAlreadyStartedProcessWithinTheBound() throws Exception {
    var started = new java.util.concurrent.CountDownLatch(1);
    var process = new java.util.concurrent.atomic.AtomicReference<Process>();
    var token = new com.mewcode.agent.CancellationToken();
    var runner =
        new GitCommandRunner(
            java.time.Duration.ofSeconds(10),
            builder -> {
              var child = new ProcessBuilder("/bin/sh", "-c", "exec sleep 10").start();
              process.set(child);
              started.countDown();
              return child;
            });
    try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var result = threads.submit(() -> runner.run(temporary, token, "status"));
      assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
      token.cancel();
      var error =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> result.get(3, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(error.getCause().getMessage().contains("已取消"));
      assertFalse(process.get().isAlive());
    }
  }

  @Test
  void failureDoesNotExposeRawStderrOrLauncherDetails() {
    String credential = "fake-api-key-must-not-leak";
    var runner =
        new GitCommandRunner(
            java.time.Duration.ofSeconds(2),
            builder ->
                new ProcessBuilder("/bin/sh", "-c", "printf '%s' '" + credential + "' >&2; exit 42")
                    .start());
    var error =
        assertThrows(
            WorktreeException.class,
            () -> runner.run(temporary, new com.mewcode.agent.CancellationToken(), "status"));
    assertFalse(error.toString().contains(credential));
    assertTrue(error.getMessage().contains("42"));
    assertTrue(error.getMessage().contains(temporary.toString()));
    var unavailable =
        new GitCommandRunner(
            java.time.Duration.ofSeconds(2),
            builder -> {
              throw new java.io.IOException(credential);
            });
    assertFalse(
        assertThrows(
                WorktreeException.class,
                () ->
                    unavailable.run(temporary, new com.mewcode.agent.CancellationToken(), "status"))
            .toString()
            .contains(credential));
  }
}
