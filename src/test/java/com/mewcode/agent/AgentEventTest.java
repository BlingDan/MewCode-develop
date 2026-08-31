package com.mewcode.agent;

import com.mewcode.compact.CompactResult;
import com.mewcode.compact.ContextTrigger;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventTest {

    @Test
    void exposesTheContextCompactionEventKinds() {
        AgentEvent text = new AgentEvent.StreamText("hello");
        AgentEvent tool = new AgentEvent.ToolUse("call-1", "ReadFile", Map.of("path", "/tmp/a"));
        AgentEvent result = new AgentEvent.ToolResult("call-1", "ReadFile", "ok", false, 12);
        AgentEvent turn = new AgentEvent.TurnComplete(1);
        AgentEvent loop = new AgentEvent.LoopComplete(1);
        AgentEvent usage = new AgentEvent.Usage(OptionalLong.of(10), OptionalLong.of(4));
        AgentEvent error = new AgentEvent.Error("provider failed", AgentEvent.ErrorCategory.PROVIDER);
        AgentEvent started = new AgentEvent.CompactionStarted(ContextTrigger.AUTO);
        AgentEvent complete =
                new AgentEvent.CompactionComplete(new CompactResult(20_000, 4_000, true));

        assertInstanceOf(AgentEvent.StreamText.class, text);
        assertInstanceOf(AgentEvent.ToolUse.class, tool);
        assertInstanceOf(AgentEvent.ToolResult.class, result);
        assertInstanceOf(AgentEvent.TurnComplete.class, turn);
        assertInstanceOf(AgentEvent.LoopComplete.class, loop);
        assertInstanceOf(AgentEvent.Usage.class, usage);
        assertInstanceOf(AgentEvent.Error.class, error);
        assertInstanceOf(AgentEvent.CompactionStarted.class, started);
        assertInstanceOf(AgentEvent.CompactionComplete.class, complete);
        assertEquals("ReadFile", ((AgentEvent.ToolUse) tool).toolName());
        assertEquals(12, ((AgentEvent.ToolResult) result).durationMillis());
        assertEquals(10, ((AgentEvent.Usage) usage).inputTokens().orElseThrow());
        assertEquals(ContextTrigger.AUTO, ((AgentEvent.CompactionStarted) started).trigger());
        assertEquals(4_000, ((AgentEvent.CompactionComplete) complete).result().afterTokens());
        assertEquals(AgentEvent.ErrorCategory.CONTEXT,
                new AgentEvent.Error("context failed", AgentEvent.ErrorCategory.CONTEXT).category());
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
