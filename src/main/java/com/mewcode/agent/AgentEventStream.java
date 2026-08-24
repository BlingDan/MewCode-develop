package com.mewcode.agent;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** AgentEvent 的有序异步消费流。 */
public final class AgentEventStream implements AutoCloseable {

    private final BlockingQueue<AgentEvent> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public void publish(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        if (!closed.get()) queue.offer(event);
    }

    public AgentEvent poll(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        return queue.poll(timeout, unit);
    }

    public AgentEvent next() throws InterruptedException {
        while (true) {
            AgentEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
            if (event != null) return event;
            if (closed.get() && queue.isEmpty()) return null;
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void complete() {
        closed.set(true);
    }

    @Override
    public void close() {
        complete();
    }
}
