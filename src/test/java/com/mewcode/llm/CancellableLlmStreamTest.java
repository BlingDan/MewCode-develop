package com.mewcode.llm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CancellableLlmStreamTest {

    @Test
    void closeIsIdempotentAndInvokesProviderCloseOnce() {
        var queue = new LinkedBlockingQueue<StreamEvent>();
        var closes = new AtomicInteger();
        var stream = new CancellableLlmStream(queue, closes::incrementAndGet);

        stream.close();
        stream.close();

        assertTrue(stream.isClosed());
        assertEquals(1, closes.get());
    }
}
