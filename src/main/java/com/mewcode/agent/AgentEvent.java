package com.mewcode.agent;

import com.mewcode.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;

/** 一次 Agent 工具回合对 UI 暴露的事件。 */
public sealed interface AgentEvent
        permits AgentEvent.TextDelta, AgentEvent.ThinkingDelta,
        AgentEvent.ToolStarted, AgentEvent.ToolCompleted,
        AgentEvent.Completed, AgentEvent.Error {

    record TextDelta(String text) implements AgentEvent {
    }

    record ThinkingDelta(String text) implements AgentEvent {
    }

    record ToolStarted(String toolUseId,
                       String toolName,
                       Map<String, Object> arguments) implements AgentEvent {
        public ToolStarted {
            arguments = arguments == null
                    ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(arguments));
        }
    }

    record ToolCompleted(String toolUseId, String toolName, ToolResult result) implements AgentEvent {
    }

    record Completed(String stopReason) implements AgentEvent {
    }

    record Error(String message) implements AgentEvent {
    }
}
