package com.mewcode.agent;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AgentEvent 的有序异步消费流。
 *
 * <p>生产者只负责发布事件，消费者通过轮询或 {@link #next()} 读取事件，因此 Agent
 * 核心不依赖具体 UI。关闭只表示“不会再产生新事件”；队列中的尾部事件仍会先被消费。</p>
 */
public final class AgentEventStream implements AutoCloseable {

    private final BlockingQueue<AgentEvent> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 按发布顺序入队；流关闭后丢弃迟到事件，避免 Loop 结束后污染下一次交互。 */
    public void publish(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        if (!closed.get()) queue.offer(event);
    }

    /** 在指定时间内取一条事件，适合 TUI 周期性刷新。 */
    public AgentEvent poll(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        return queue.poll(timeout, unit);
    }

    /** 阻塞读取下一条事件，只有关闭且队列排空时才返回 {@code null}。 */
    public AgentEvent next() throws InterruptedException {
        while (true) {
            AgentEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
            if (event != null) return event;
            if (closed.get() && queue.isEmpty()) return null;
        }
    }

    /** 判断生产端是否已经声明不会再发布事件。 */
    public boolean isClosed() {
        return closed.get();
    }

    /** 标记流结束，不清理已有队列内容。 */
    public void complete() {
        closed.set(true);
    }

    @Override
    public void close() {
        complete();
    }
}
