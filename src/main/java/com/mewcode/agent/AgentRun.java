package com.mewcode.agent;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** 一次 Agent Loop 的运行句柄和取消边界。 */
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

    public AgentEventStream events() {
        return events;
    }

    public CancellationToken cancellationToken() {
        return cancellationToken;
    }

    public State state() {
        return state.get();
    }

    public boolean isRunning() {
        return state() == State.RUNNING;
    }

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

    public void removeCancellationHook(Runnable hook) {
        cancellationHooks.remove(hook);
    }

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
