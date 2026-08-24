package com.mewcode.llm;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** provider 流的可取消包装；close 操作必须是幂等的。 */
public final class CancellableLlmStream implements AutoCloseable {

    private final BlockingQueue<StreamEvent> events;
    private final Runnable closeAction;
    private final AtomicBoolean closed = new AtomicBoolean();

    public CancellableLlmStream(BlockingQueue<StreamEvent> events, Runnable closeAction) {
        this.events = Objects.requireNonNull(events, "events");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
    }

    public BlockingQueue<StreamEvent> events() {
        return events;
    }

    public StreamEvent next() throws InterruptedException {
        while (true) {
            StreamEvent event = events.poll(100, TimeUnit.MILLISECONDS);
            if (event != null) return event;
            if (closed.get() && events.isEmpty()) return null;
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            closeAction.run();
        } catch (RuntimeException ignored) {
            // 取消是 best-effort 的，不能因为底层 close 异常阻塞 Loop 收尾。
        }
    }
}
