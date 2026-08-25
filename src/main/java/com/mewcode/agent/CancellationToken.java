package com.mewcode.agent;

import java.util.concurrent.atomic.AtomicBoolean;

/** 一次 AgentRun 内共享的、幂等的取消信号。 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    /** 发出取消信号；重复取消返回 {@code false}，不会重复触发语义。 */
    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    /** 查询取消信号，供长任务在安全检查点快速退出。 */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 在当前执行点把取消信号转换为运行时异常，便于中断工具任务。 */
    public void throwIfCancelled() {
        if (isCancelled()) throw new CancellationException();
    }

    public static final class CancellationException extends RuntimeException {
        public CancellationException() {
            super("Operation cancelled");
        }
    }
}
