package com.mewcode.agent;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次 Agent Loop 的运行句柄和取消边界。
 *
 * <p>取消先设置共享 token，再执行 provider/工具注册的清理 hook；hook 失败会被隔离，
 * 这样 Esc 触发的快速退出不会因为某个底层连接关闭异常而卡住整个 Loop。</p>
 */
public final class AgentRun implements AutoCloseable {

    public enum State {
        RUNNING,
        CANCELLED,
        COMPLETED
    }

    private final AgentEventStream events = new AgentEventStream();
    private final CancellationToken cancellationToken = new CancellationToken();
    private final CopyOnWriteArrayList<Runnable> cancellationHooks = new CopyOnWriteArrayList<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);

    /** 返回本次运行的异步事件流。 */
    public AgentEventStream events() {
        return events;
    }

    /** 返回供 provider 和工具共享的取消 token。 */
    public CancellationToken cancellationToken() {
        return cancellationToken;
    }

    /** 返回当前运行状态。 */
    public State state() {
        return state.get();
    }

    /** 判断运行是否仍可被取消。 */
    public boolean isRunning() {
        return state() == State.RUNNING;
    }

    /** 幂等取消本次 Loop，并立即触发所有底层资源的关闭 hook。 */
    public boolean cancel() {
        if (!state.compareAndSet(State.RUNNING, State.CANCELLED)) return false;
        cancellationToken.cancel();
        for (Runnable hook : cancellationHooks) {
            try {
                hook.run();
            } catch (RuntimeException ignored) {
                // 取消必须继续通知其他句柄，单个资源关闭失败不能阻塞收尾。
            }
        }
        cancellationHooks.clear();
        return true;
    }

    /** 注册取消清理动作；并发竞态下若已取消会立即补执行一次。 */
    public void addCancellationHook(Runnable hook) {
        Objects.requireNonNull(hook, "hook");
        if (!isRunning()) {
            if (state() == State.CANCELLED) safelyRun(hook);
            return;
        }
        cancellationHooks.add(hook);
        if (!isRunning() && cancellationHooks.remove(hook) && state() == State.CANCELLED) {
            safelyRun(hook);
        }
    }

    /** 移除已经完成正常收尾的清理动作。 */
    public void removeCancellationHook(Runnable hook) {
        cancellationHooks.remove(hook);
    }

    /** 正常关闭运行句柄和事件流，保留事件队列中尚未消费的尾部事件。 */
    public void complete() {
        state.compareAndSet(State.RUNNING, State.COMPLETED);
        cancellationHooks.clear();
        events.complete();
    }

    @Override
    public void close() {
        if (isRunning()) cancel();
        events.complete();
    }

    private static void safelyRun(Runnable hook) {
        try {
            hook.run();
        } catch (RuntimeException ignored) {
            // 取消 hook 是 best-effort 的资源清理。
        }
    }
}
