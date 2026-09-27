package com.mewcode.worktree;

import com.mewcode.agent.CancellationToken;
import com.mewcode.config.WorktreeConfig;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 临时归属、过期且空闲、成果安全三层检查；所有删除复用默认保护。 */
public final class StaleCleanup implements AutoCloseable {
  private final WorktreeManager manager;
  private final Duration cutoff;
  private final int intervalMinutes;
  private final Consumer<String> diagnostics;
  private final AtomicBoolean scanning = new AtomicBoolean();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            var thread = new Thread(runnable, "mewcode-worktree-cleanup");
            thread.setDaemon(true);
            return thread;
          });
  private volatile CancellationToken current;

  public StaleCleanup(
      WorktreeManager manager, WorktreeConfig config, Consumer<String> diagnostics) {
    config.validate();
    this.manager = manager;
    cutoff = Duration.ofHours(config.getStaleCutoffHours());
    intervalMinutes = config.getCleanupIntervalMinutes();
    this.diagnostics = diagnostics == null ? ignored -> {} : diagnostics;
  }

  public void start() {
    if (closed.get() || !started.compareAndSet(false, true)) return;
    scheduler.scheduleWithFixedDelay(
        () -> cleanup(Instant.now(), new CancellationToken()),
        intervalMinutes,
        intervalMinutes,
        TimeUnit.MINUTES);
  }

  public int cleanup(Instant now, CancellationToken token) {
    if (closed.get() || !scanning.compareAndSet(false, true)) return 0;
    current = token;
    int removed = 0;
    try {
      // 没有记录时只读文件系统，不强制检测 Git 或执行 Git 命令。
      for (var candidate : manager.store.cleanupCandidates(manager.repositoryRoot(), diagnostics)) {
        if (closed.get() || token.isCancelled()) break;
        try {
          if (manager.cleanupTemporary(candidate, now, cutoff, token)) removed++;
        } catch (RuntimeException error) {
          diagnostics.accept("过期工作树已保留：" + candidate.path + "；归属、占用或成果检查未通过。");
        }
      }
    } catch (IOException | RuntimeException error) {
      diagnostics.accept("工作树过期扫描未能验证记录，已保留资源。");
    } finally {
      current = null;
      scanning.set(false);
    }
    return removed;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    var token = current;
    if (token != null) token.cancel();
    scheduler.shutdownNow();
    try {
      scheduler.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }
}
