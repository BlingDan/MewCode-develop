package com.mewcode.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventTest {

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
