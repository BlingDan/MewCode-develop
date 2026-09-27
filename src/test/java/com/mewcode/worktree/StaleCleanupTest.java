package com.mewcode.worktree;

import static org.junit.jupiter.api.Assertions.*;

import com.mewcode.agent.CancellationToken;
import com.mewcode.config.WorktreeConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaleCleanupTest {
  @TempDir Path root;

  @Test
  void scansPersistentTemporaryProvenanceThenExpiryUsageAndResults() throws Exception {
    new GitRepositoryFixture(root);
    var config = new WorktreeConfig();
    var manager = new WorktreeManager(root, config, "session");
    var token = new CancellationToken();
    String head = manager.freezeHead(root, token);
    String good = "temp-agent-1-12345678",
        dirty = "temp-agent-2-12345678",
        recent = "temp-agent-3-12345678";
    manager.createResource(root, good, head, true, "agent-1-12345678", token);
    var modified = manager.createResource(root, dirty, head, true, "agent-2-12345678", token);
    manager.createResource(root, recent, head, true, "agent-3-12345678", token);
    manager.create(root, "manual", token);
    Files.writeString(modified.path.resolve("notes.txt"), "keep");
    Instant now = Instant.now().plus(Duration.ofHours(25));
    var last = manager.store.loadResource(root, recent).orElseThrow();
    last.lastUsedAt = now;
    manager.store.saveResource(root, last);
    Files.writeString(root.resolve(".mewcode/worktree-state/resources/corrupt.json"), "broken");
    // 模拟新进程扫描已有记录；不依靠本进程创建列表。
    var restarted = new WorktreeManager(root, config, "session-next");
    try (var cleanup = new StaleCleanup(restarted, config, ignored -> {})) {
      assertEquals(1, cleanup.cleanup(now, new CancellationToken()));
    }
    assertFalse(Files.exists(root.resolve(".mewcode/worktrees/" + good)));
    assertTrue(Files.exists(modified.path));
    assertTrue(Files.exists(last.path));
    assertTrue(Files.exists(root.resolve(".mewcode/worktrees/manual")));
  }

  @Test
  void idleNonGitDirectoryNeedsNoGitDuringAnEmptyScan() throws Exception {
    Files.createDirectories(root);
    var config = new WorktreeConfig();
    var manager = new WorktreeManager(root, config, "session");
    try (var cleanup = new StaleCleanup(manager, config, ignored -> {})) {
      assertEquals(
          0, cleanup.cleanup(Instant.now().plus(Duration.ofDays(10)), new CancellationToken()));
    }
    assertFalse(Files.exists(root.resolve(".git")));
  }

  @Test
  void concurrentScanIsSkippedAndCloseCancelsTheActiveScan() throws Exception {
    new GitRepositoryFixture(root);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var block = new java.util.concurrent.atomic.AtomicBoolean();
    var runner =
        new GitCommandRunner(
            Duration.ofSeconds(3),
            builder -> {
              if (block.get() && builder.command().contains("status")) {
                entered.countDown();
                try {
                  if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    throw new java.io.IOException("test timeout");
                } catch (InterruptedException error) {
                  Thread.currentThread().interrupt();
                  throw new java.io.IOException("interrupted");
                }
              }
              return builder.start();
            });
    var config = new WorktreeConfig();
    var manager = new WorktreeManager(root, config, "session", runner);
    var token = new CancellationToken();
    var resource =
        manager.createResource(
            root,
            "temp-agent-9-12345678",
            manager.freezeHead(root, token),
            true,
            "agent-9-12345678",
            token);
    block.set(true);
    var cleanup = new StaleCleanup(manager, config, ignored -> {});
    Instant now = Instant.now().plus(Duration.ofDays(2));
    var running =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () -> cleanup.cleanup(now, new CancellationToken()));
    assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
    try {
      assertEquals(0, cleanup.cleanup(now, new CancellationToken()));
      cleanup.close();
    } finally {
      release.countDown();
    }
    assertEquals(0, running.get(3, java.util.concurrent.TimeUnit.SECONDS));
    assertTrue(Files.exists(resource.path));
    assertEquals(0, cleanup.cleanup(now, new CancellationToken()));
  }
}
