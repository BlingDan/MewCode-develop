package com.mewcode.hook;

import com.mewcode.config.HookConfigLoader;
import com.mewcode.tool.support.CommandRunner;
import com.mewcode.util.Closeables;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** 按声明顺序分派 Hook，并隔离后台动作失败。 */
public final class HookEngine implements AutoCloseable {
  private final HookConfigLoader.LoadedHooks loaded;
  private final HookActionExecutor actionExecutor;
  private final Consumer<String> diagnostics;
  private final ExecutorService background =
      java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
  private final Map<HookSessionState, Set<Future<?>>> tasks = new ConcurrentHashMap<>();
  private volatile boolean closed;

  public HookEngine(
      HookConfigLoader.LoadedHooks loaded,
      CommandRunner commandRunner,
      Consumer<String> diagnostics) {
    this.loaded = java.util.Objects.requireNonNull(loaded, "loaded");
    this.diagnostics = diagnostics == null ? ignored -> {} : diagnostics;
    this.actionExecutor =
        new HookActionExecutor(
            commandRunner,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
            this.diagnostics);
  }

  public HookConfigLoader.LoadedHooks loaded() {
    return loaded;
  }

  public Optional<HookRejection> dispatch(HookInvocation invocation) {
    if (invocation == null || closed || invocation.state().isClosed()) return Optional.empty();
    for (HookRule rule : loaded.rules()) {
      if (closed || invocation.state().isClosed()) break;
      if (rule.event() != invocation.event()) continue;
      if (rule.condition().isPresent()
          && !rule.condition().orElseThrow().matches(invocation.payload())) {
        continue;
      }
      if (rule.async()) {
        schedule(rule, invocation);
        continue;
      }
      if (rule.onlyOnce() && !invocation.state().tryStartOnce(rule.name())) continue;
      Optional<HookRejection> rejection = runPinned(rule, invocation);
      if (rejection.isPresent()) return rejection;
    }
    return Optional.empty();
  }

  public void cancelSession(HookSessionState state) {
    if (state == null) return;
    state.close();
    Set<Future<?>> running = tasks.get(state);
    if (running == null) return;
    for (Future<?> task : running) task.cancel(true);
  }

  private void schedule(HookRule rule, HookInvocation invocation) {
    if (rule.onlyOnce() && !invocation.state().tryStartOnce(rule.name())) return;
    HookSessionState state = invocation.state();
    if (closed || state.isClosed()) {
      if (rule.onlyOnce()) state.releaseOnceStart(rule.name());
      return;
    }
    Set<Future<?>> stateTasks =
        tasks.computeIfAbsent(state, ignored -> ConcurrentHashMap.newKeySet());
    AutoCloseable use;
    try {
      use = acquireUse(invocation);
    } catch (RuntimeException error) {
      if (rule.onlyOnce()) state.releaseOnceStart(rule.name());
      diagnostics.accept("Hook 所属工作树不可用：" + rule.name());
      return;
    }
    var started = new java.util.concurrent.atomic.AtomicInteger();
    var entered = new java.util.concurrent.atomic.AtomicBoolean();
    FutureTask<Void> task =
        new FutureTask<>(
            () -> {
              entered.set(true);
              try {
                run(rule, invocation);
                return null;
              } finally {
                Closeables.closeQuietly(use);
              }
            }) {
          @Override
          public void run() {
            if (!started.compareAndSet(0, 1)) return;
            try {
              super.run();
            } finally {
              if (!entered.get() && rule.onlyOnce()) state.releaseOnceStart(rule.name());
              Closeables.closeQuietly(use);
              started.set(2);
              removeTask(state, this);
            }
          }

          @Override
          protected void done() {
            // cancel(true) 的 done 可能早于 Callable 真正返回。
            if (started.compareAndSet(0, 2)) {
              Closeables.closeQuietly(use);
              if (rule.onlyOnce()) state.releaseOnceStart(rule.name());
              removeTask(state, this);
            }
          }
        };
    stateTasks.add(task);
    try {
      background.execute(task);
    } catch (RejectedExecutionException error) {
      task.cancel(false);
      stateTasks.remove(task);
      if (stateTasks.isEmpty()) tasks.remove(state, stateTasks);
      if (rule.onlyOnce()) state.releaseOnceStart(rule.name());
      diagnostics.accept("Hook 后台任务提交失败：" + rule.name() + " " + rule.event().configName());
    }
  }

  public boolean hasPending(HookSessionState state) {
    Set<Future<?>> running = tasks.get(state);
    return running != null && !running.isEmpty();
  }

  public boolean awaitSessionIdle(HookSessionState state, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (hasPending(state)) {
      if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return false;
      java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
    }
    return true;
  }

  private AutoCloseable acquireUse(HookInvocation invocation) {
    var context = invocation.executionContext();
    return context == null || context.workspaceScope() == null
        ? () -> {}
        : context.workspaceScope().retain();
  }

  private Optional<HookRejection> runPinned(HookRule rule, HookInvocation invocation) {
    AutoCloseable use = null;
    try {
      use = acquireUse(invocation);
      return run(rule, invocation);
    } catch (RuntimeException error) {
      diagnostics.accept("Hook 所属工作树不可用：" + rule.name());
      return Optional.empty();
    } finally {
      Closeables.closeQuietly(use);
    }
  }

  private Optional<HookRejection> run(HookRule rule, HookInvocation invocation) {
    try {
      return actionExecutor.execute(rule, invocation);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      diagnostics.accept("Hook 执行被中断：" + rule.name() + " " + rule.event().configName());
      return Optional.empty();
    } catch (Exception error) {
      diagnostics.accept("Hook 执行失败：" + rule.name() + " " + rule.event().configName());
      return Optional.empty();
    } finally {
      if (rule.onlyOnce()) invocation.state().markExecuted(rule.name());
    }
  }

  private void removeTask(HookSessionState state, Future<?> task) {
    Set<Future<?>> stateTasks = tasks.get(state);
    if (stateTasks == null) return;
    stateTasks.remove(task);
    if (stateTasks.isEmpty()) tasks.remove(state, stateTasks);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    for (Map.Entry<HookSessionState, Set<Future<?>>> entry : tasks.entrySet()) {
      entry.getKey().close();
      for (Future<?> task : entry.getValue()) task.cancel(true);
    }
    background.shutdownNow();
    try {
      background.awaitTermination(
          Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }
}
