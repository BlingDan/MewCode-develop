package com.mewcode.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventTest {

    @Test
    void exposesTheSevenProviderNeutralEventKinds() {
        AgentEvent text = new AgentEvent.StreamText("hello");
        AgentEvent tool = new AgentEvent.ToolUse("call-1", "ReadFile", Map.of("path", "/tmp/a"));
        AgentEvent result = new AgentEvent.ToolResult("call-1", "ReadFile", "ok", false, 12);
        AgentEvent turn = new AgentEvent.TurnComplete(1);
        AgentEvent loop = new AgentEvent.LoopComplete(1);
        AgentEvent usage = new AgentEvent.Usage(OptionalLong.of(10), OptionalLong.of(4));
        AgentEvent error = new AgentEvent.Error("provider failed", AgentEvent.ErrorCategory.PROVIDER);

        assertInstanceOf(AgentEvent.StreamText.class, text);
        assertInstanceOf(AgentEvent.ToolUse.class, tool);
        assertInstanceOf(AgentEvent.ToolResult.class, result);
        assertInstanceOf(AgentEvent.TurnComplete.class, turn);
        assertInstanceOf(AgentEvent.LoopComplete.class, loop);
        assertInstanceOf(AgentEvent.Usage.class, usage);
        assertInstanceOf(AgentEvent.Error.class, error);
        assertEquals("ReadFile", ((AgentEvent.ToolUse) tool).toolName());
        assertEquals(12, ((AgentEvent.ToolResult) result).durationMillis());
        assertEquals(10, ((AgentEvent.Usage) usage).inputTokens().orElseThrow());
    }

    @Test
    void makesToolInputImmutableAndCanRepresentUnknownUsage() {
        var input = new java.util.LinkedHashMap<String, Object>();
        input.put("path", "/tmp/a");
        var event = new AgentEvent.ToolUse("call-1", "ReadFile", input);
        input.put("path", "/tmp/changed");

        assertEquals("/tmp/a", event.input().get("path"));
        assertThrows(UnsupportedOperationException.class,
                () -> event.input().put("other", "value"));

        var unknown = new AgentEvent.Usage(OptionalLong.empty(), OptionalLong.empty());
        assertTrue(unknown.inputTokens().isEmpty());
        assertTrue(unknown.outputTokens().isEmpty());
    }
}
