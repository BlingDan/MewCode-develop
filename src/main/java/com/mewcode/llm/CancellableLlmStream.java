package com.mewcode.llm;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * provider 流的可取消包装；close 操作必须是幂等的。
 *
 * <p>事件队列与底层 HTTP 响应的关闭动作分离：消费者仍可读完已经入队的事件，
 * 但不会继续等待新的 provider 数据。</p>
 */
public final class CancellableLlmStream implements AutoCloseable {

    private final BlockingQueue<StreamEvent> events;
    private final Runnable closeAction;
    private final AtomicBoolean closed = new AtomicBoolean();

    public CancellableLlmStream(BlockingQueue<StreamEvent> events, Runnable closeAction) {
        this.events = Objects.requireNonNull(events, "events");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
    }

    /** 返回底层事件队列，供旧式调用方直接消费。 */
    public BlockingQueue<StreamEvent> events() {
        return events;
    }

    /** 阻塞读取下一条事件；关闭且队列排空时返回 {@code null}。 */
    public StreamEvent next() throws InterruptedException {
        while (true) {
            StreamEvent event = events.poll(100, TimeUnit.MILLISECONDS);
            if (event != null) return event;
            if (closed.get() && events.isEmpty()) return null;
        }
    }

    /** 判断调用方是否已经请求关闭流。 */
    public boolean isClosed() {
        return closed.get();
    }

    /** 立即请求底层连接和 worker 停止，重复调用不会重复关闭。 */
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
