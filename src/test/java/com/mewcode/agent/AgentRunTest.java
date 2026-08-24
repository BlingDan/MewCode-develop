package com.mewcode.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunTest {

    @Test
    void publishesEventsInOrderAndClosesAfterCompletion() throws Exception {
        var run = new AgentRun();
        run.events().publish(new AgentEvent.StreamText("one"));
        run.events().publish(new AgentEvent.LoopComplete(1));
        run.complete();

        assertEquals(List.of("one", "done"), List.of(
                ((AgentEvent.StreamText) run.events().next()).text(),
                run.events().next() instanceof AgentEvent.LoopComplete ? "done" : "wrong"));
        assertNull(run.events().next());
        assertFalse(run.isRunning());
        assertEquals(AgentRun.State.COMPLETED, run.state());
    }

    @Test
    void cancellationIsIdempotentAndRunsHooksOnlyOnce() {
        var run = new AgentRun();
        var cancellations = new AtomicInteger();
        run.addCancellationHook(cancellations::incrementAndGet);

        assertTrue(run.cancel());
        assertFalse(run.cancel());
        assertEquals(1, cancellations.get());
        assertTrue(run.cancellationToken().isCancelled());
        assertEquals(AgentRun.State.CANCELLED, run.state());
    }

    @Test
    void hookAddedAfterCancellationRunsImmediately() {
        var run = new AgentRun();
        run.cancel();
        var calls = new AtomicInteger();

        run.addCancellationHook(calls::incrementAndGet);

        assertEquals(1, calls.get());
    }
}
