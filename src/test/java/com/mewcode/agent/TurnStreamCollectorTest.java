package com.mewcode.agent;

import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.StreamEvent;
import com.mewcode.conversation.ThinkingBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TurnStreamCollectorTest {

    @Test
    void forwardsTextAndToolUseWhileReturningACompleteTurn() throws Exception {
        var events = new LinkedBlockingQueue<StreamEvent>();
        events.add(new StreamEvent.TextDelta("before "));
        events.add(new StreamEvent.ThinkingDelta("internal", "signature-1"));
        events.add(new StreamEvent.Usage(OptionalLong.of(7), OptionalLong.of(2)));
        events.add(new StreamEvent.ToolCallComplete("call-1", "ReadFile",
                Map.of("path", "/tmp/a")));
        events.add(new StreamEvent.TextDelta("after"));
        events.add(new StreamEvent.StreamEnd("tool_use"));
        var run = new AgentRun();

        var turn = new TurnStreamCollector(new TokenUsageAccumulator())
                .collect(run, new CancellableLlmStream(events, () -> { }), 1);

        assertTrue(turn.complete());
        assertEquals("before after", turn.text());
        assertEquals(1, turn.calls().size());
        assertEquals("ReadFile", turn.calls().getFirst().toolName());
        assertEquals(OptionalLong.of(7), turn.inputTokens());
        assertEquals(OptionalLong.of(2), turn.outputTokens());
        var thinking = turn.blocks().stream()
                .filter(block -> block instanceof ThinkingBlock)
                .map(block -> (ThinkingBlock) block)
                .findFirst()
                .orElseThrow();
        assertEquals("internal", thinking.text());
        assertEquals("signature-1", thinking.signature());

        var published = drain(run.events());
        assertEquals(4, published.size());
        assertInstanceOf(AgentEvent.StreamText.class, published.get(0));
        assertInstanceOf(AgentEvent.Usage.class, published.get(1));
        assertInstanceOf(AgentEvent.ToolUse.class, published.get(2));
        assertInstanceOf(AgentEvent.StreamText.class, published.get(3));
    }

    @Test
    void dropsIncompleteTurnWhenCancellationArrives() throws Exception {
        var events = new LinkedBlockingQueue<StreamEvent>();
        events.add(new StreamEvent.TextDelta("partial"));
        var run = new AgentRun();
        var stream = new CancellableLlmStream(events, () -> { });
        run.cancel();

        var turn = new TurnStreamCollector(new TokenUsageAccumulator())
                .collect(run, stream, 1);

        assertFalse(turn.complete());
        assertEquals("", turn.text());
        assertTrue(drain(run.events()).isEmpty());
    }

    @Test
    void deduplicatesUsageUpdatesAndMarksMissingUsageUnknown() throws Exception {
        var events = new LinkedBlockingQueue<StreamEvent>();
        events.add(new StreamEvent.Usage(OptionalLong.of(7), OptionalLong.of(2)));
        events.add(new StreamEvent.Usage(OptionalLong.of(7), OptionalLong.of(3)));
        events.add(new StreamEvent.StreamEnd("end_turn"));
        var run = new AgentRun();

        var turn = new TurnStreamCollector(new TokenUsageAccumulator())
                .collect(run, new CancellableLlmStream(events, () -> { }), 1);

        assertEquals(OptionalLong.of(7), turn.inputTokens());
        assertEquals(OptionalLong.of(3), turn.outputTokens());
        var published = drain(run.events());
        var lastUsage = published.stream()
                .filter(event -> event instanceof AgentEvent.Usage)
                .map(event -> (AgentEvent.Usage) event)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertEquals(OptionalLong.of(7), lastUsage.inputTokens());
        assertEquals(OptionalLong.of(3), lastUsage.outputTokens());

        var noUsageEvents = new LinkedBlockingQueue<StreamEvent>();
        noUsageEvents.add(new StreamEvent.StreamEnd("end_turn"));
        var noUsageRun = new AgentRun();
        var noUsageTurn = new TurnStreamCollector(new TokenUsageAccumulator())
                .collect(noUsageRun, new CancellableLlmStream(noUsageEvents, () -> { }), 1);
        assertTrue(noUsageTurn.inputTokens().isEmpty());
        assertTrue(noUsageTurn.outputTokens().isEmpty());
        var unknown = drain(noUsageRun.events()).stream()
                .filter(event -> event instanceof AgentEvent.Usage)
                .map(event -> (AgentEvent.Usage) event)
                .findFirst()
                .orElseThrow();
        assertTrue(unknown.inputTokens().isEmpty());
        assertTrue(unknown.outputTokens().isEmpty());
    }

    @Test
    void keepsAllUsageDimensionsForContextEstimation() throws Exception {
        var events = new LinkedBlockingQueue<StreamEvent>();
        var usage = new StreamEvent.Usage(
                OptionalLong.of(11),
                OptionalLong.of(2),
                OptionalLong.of(3),
                OptionalLong.of(7));
        events.add(usage);
        events.add(new StreamEvent.StreamEnd("end_turn"));

        var turn = new TurnStreamCollector(new TokenUsageAccumulator())
                .collect(new AgentRun(), new CancellableLlmStream(events, () -> { }), 1);

        assertEquals(usage, turn.usage().orElseThrow());
        assertEquals(OptionalLong.of(11), turn.inputTokens());
        assertEquals(OptionalLong.of(7), turn.outputTokens());
    }

    @Test
    void preservesContextLengthErrorKindWithoutCommittingPartialTurn() throws Exception {
        var events = new LinkedBlockingQueue<StreamEvent>();
        events.add(new StreamEvent.TextDelta("partial"));
        events.add(new StreamEvent.Error(
                "prompt too long",
                StreamEvent.ErrorKind.CONTEXT_LENGTH));

        var turn = new TurnStreamCollector(new TokenUsageAccumulator())
                .collect(new AgentRun(), new CancellableLlmStream(events, () -> { }), 1);

        assertFalse(turn.complete());
        assertEquals(StreamEvent.ErrorKind.CONTEXT_LENGTH, turn.errorKind());
        assertEquals("partial", turn.text());
    }

    private static List<AgentEvent> drain(AgentEventStream stream) throws Exception {
        var result = new ArrayList<AgentEvent>();
        while (true) {
            AgentEvent event = stream.poll(10, TimeUnit.MILLISECONDS);
            if (event == null) return result;
            result.add(event);
        }
    }
}
