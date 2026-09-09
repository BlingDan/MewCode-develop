package com.mewcode.hook;

import com.mewcode.config.HookConfigLoader;
import com.mewcode.tool.support.CommandRunner;
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
        if (!schedule(rule, invocation)) continue;
        continue;
      }
      if (rule.onlyOnce() && !invocation.state().tryStartOnce(rule.name())) continue;
      Optional<HookRejection> rejection = run(rule, invocation);
      if (rejection.isPresent()) return rejection;
    }
    return Optional.empty();
  }

  public void cancelSession(HookSessionState state) {
    if (state == null) return;
    state.close();
    Set<Future<?>> running = tasks.remove(state);
    if (running == null) return;
    for (Future<?> task : running) task.cancel(true);
  }

  private boolean schedule(HookRule rule, HookInvocation invocation) {
    if (rule.onlyOnce() && !invocation.state().tryStartOnce(rule.name())) return false;
    HookSessionState state = invocation.state();
    if (closed || state.isClosed()) {
      if (rule.onlyOnce()) state.releaseOnceStart(rule.name());
      return false;
    }
    Set<Future<?>> stateTasks =
        tasks.computeIfAbsent(state, ignored -> ConcurrentHashMap.newKeySet());
    FutureTask<Void> task =
        new FutureTask<>(
            () -> {
              run(rule, invocation);
              return null;
            }) {
          @Override
          protected void done() {
            removeTask(state, this);
          }
        };
    stateTasks.add(task);
    try {
      background.execute(task);
      return true;
    } catch (RejectedExecutionException error) {
      stateTasks.remove(task);
      if (stateTasks.isEmpty()) tasks.remove(state, stateTasks);
      if (rule.onlyOnce()) state.releaseOnceStart(rule.name());
      diagnostics.accept("Hook 后台任务提交失败：" + rule.name() + " " + rule.event().configName());
      return false;
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
    tasks.clear();
    background.shutdownNow();
    try {
      background.awaitTermination(
          Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }
}
