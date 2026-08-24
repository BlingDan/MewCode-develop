package com.mewcode.agent;

import java.util.concurrent.atomic.AtomicBoolean;

/** 一次 AgentRun 内共享的、幂等的取消信号。 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() {
        if (isCancelled()) throw new CancellationException();
    }

    public static final class CancellationException extends RuntimeException {
        public CancellationException() {
            super("Operation cancelled");
        }
    }
}
