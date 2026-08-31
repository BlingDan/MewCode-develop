package com.mewcode.testsupport;

import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.StreamEvent;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/** 测试用的确定性 Provider 流工厂。 */
public final class FakeCancellableLlmStream {

    private final List<StreamEvent> events;

    public FakeCancellableLlmStream(List<StreamEvent> events) {
        this.events = List.copyOf(events);
    }

    /** 创建一次只消费预置事件的可取消流。 */
    public CancellableLlmStream open() {
        var queue = new LinkedBlockingQueue<StreamEvent>();
        queue.addAll(events);
        return new CancellableLlmStream(queue, () -> {});
    }
}
