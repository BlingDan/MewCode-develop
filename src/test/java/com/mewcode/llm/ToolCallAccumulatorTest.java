package com.mewcode.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallAccumulatorTest {

    @Test
    void accumulatesInterleavedJsonFragmentsIndependentlyById() {
        var accumulator = new ToolCallAccumulator();
        accumulator.start("one", "ReadFile");
        accumulator.start("two", "Grep");
        accumulator.append("one", "{\"path\":\"");
        accumulator.append("two", "{\"pattern\":\"");
        accumulator.append("one", "/tmp/a\"}");
        accumulator.append("two", "needle\"}");

        var first = accumulator.finish("one");
        var second = accumulator.finish("two");
        assertInstanceOf(StreamEvent.ToolCallComplete.class, first);
        assertInstanceOf(StreamEvent.ToolCallComplete.class, second);
        assertEquals("/tmp/a", ((StreamEvent.ToolCallComplete) first).arguments().get("path"));
        assertEquals("needle", ((StreamEvent.ToolCallComplete) second).arguments().get("pattern"));
    }

    @Test
    void reportsMalformedJsonWithoutThrowingAndFinishesOtherCalls() {
        var accumulator = new ToolCallAccumulator();
        accumulator.start("bad", "ReadFile");
        accumulator.start("good", "Grep");
        accumulator.append("bad", "{not-json");
        accumulator.append("good", "{}");

        List<StreamEvent> events = accumulator.finishAll();
        assertEquals(2, events.size());
        assertInstanceOf(StreamEvent.ToolCallParseError.class, events.get(0));
        assertInstanceOf(StreamEvent.ToolCallComplete.class, events.get(1));
        assertTrue(((StreamEvent.ToolCallParseError) events.get(0)).message().contains("JSON"));
    }
}
