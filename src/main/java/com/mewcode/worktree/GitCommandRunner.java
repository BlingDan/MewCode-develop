package com.mewcode.worktree;

import com.mewcode.agent.CancellationToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** 参数数组、显式 cwd、有界输出与进程树收口统一放在生命周期内部。 */
final class GitCommandRunner {
  @FunctionalInterface
  interface Launcher {
    Process start(ProcessBuilder builder) throws IOException;
  }

  record Result(int exitCode, String output) {}

  private final Duration timeout;
  private final Launcher launcher;

  GitCommandRunner() {
    this(Duration.ofSeconds(120), ProcessBuilder::start);
  }

  GitCommandRunner(Duration timeout, Launcher launcher) {
    if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout");
    this.timeout = timeout;
    this.launcher = launcher;
  }

  String run(Path cwd, CancellationToken token, String... args) {
    Result result = execute(cwd, token, args);
    if (result.exitCode != 0) throw failure("Git 返回非零退出码 " + result.exitCode, cwd);
    return result.output;
  }

  Result execute(Path cwd, CancellationToken token, String... args) {
    if (token.isCancelled()) throw failure("操作已取消", cwd);
    var argv = new ArrayList<String>();
    argv.add("git");
    argv.addAll(Arrays.asList(args));
    ProcessBuilder builder =
        new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true);
    // 不继承可能改变仓库或引用命名空间的宿主环境。
    builder.environment().keySet().removeIf(k -> k.startsWith("GIT_"));
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
    builder.environment().put("LC_ALL", "C");
    Process process;
    try {
      process = launcher.start(builder);
    } catch (IOException error) {
      throw failure("无法启动 Git 进程", cwd);
    }
    var bytes = new java.io.ByteArrayOutputStream();
    var readFailed = new java.util.concurrent.atomic.AtomicBoolean();
    Thread reader =
        Thread.startVirtualThread(
            () -> {
              try (var input = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                  synchronized (bytes) {
                    if (bytes.size() + count > 1_048_576) readFailed.set(true);
                    else bytes.write(buffer, 0, count);
                  }
                }
              } catch (IOException error) {
                readFailed.set(true);
              }
            });
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      while (process.isAlive()) {
        if (token.isCancelled() || System.nanoTime() >= deadline) {
          stop(process, cwd);
          throw failure(token.isCancelled() ? "操作已取消，进程已停止" : "操作超时，进程已停止", cwd);
        }
        process.waitFor(25, TimeUnit.MILLISECONDS);
      }
      reader.join(2000);
      if (reader.isAlive() || readFailed.get()) throw failure("无法确认完整 Git 输出", cwd);
      return new Result(process.exitValue(), bytes.toString(StandardCharsets.UTF_8));
    } catch (InterruptedException error) {
      stop(process, cwd);
      Thread.currentThread().interrupt();
      throw failure("操作被中断", cwd);
    } finally {
      if (process.isAlive()) stop(process, cwd);
      try {
        process.getInputStream().close();
      } catch (IOException ignored) {
      }
      try {
        reader.join(2000);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void stop(Process process, Path cwd) {
    var children = process.descendants().toList();
    children.reversed().forEach(ProcessHandle::destroyForcibly);
    try {
      process.waitFor(200, TimeUnit.MILLISECONDS);
    } catch (InterruptedException ignored) {
    }
    if (process.isAlive()) process.destroyForcibly();
    long limit = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while ((process.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive))
        && System.nanoTime() < limit) {
      java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
    }
    if (process.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive))
      throw failure("无法确认进程实际停止，须保留目录", cwd);
  }

  private WorktreeException failure(String reason, Path cwd) {
    return new WorktreeException("Git", reason, cwd, null);
  }
}
