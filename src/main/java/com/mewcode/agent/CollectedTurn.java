package com.mewcode.agent;

import com.mewcode.conversation.ContentBlock;
import com.mewcode.tool.ToolCall;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** 一轮 provider 流的完整领域快照。 */
public record CollectedTurn(
        List<ContentBlock> blocks,
        List<ToolCall> calls,
        Map<String, String> parseErrors,
        String text,
        String thinking,
        String stopReason,
        String error,
        OptionalLong inputTokens,
        OptionalLong outputTokens,
        boolean complete) {

    public CollectedTurn {
        blocks = List.copyOf(blocks == null ? List.of() : blocks);
        calls = List.copyOf(calls == null ? List.of() : calls);
        parseErrors = Map.copyOf(parseErrors == null ? Map.of() : parseErrors);
        text = text == null ? "" : text;
        thinking = thinking == null ? "" : thinking;
        stopReason = stopReason == null ? "" : stopReason;
        inputTokens = inputTokens == null ? OptionalLong.empty() : inputTokens;
        outputTokens = outputTokens == null ? OptionalLong.empty() : outputTokens;
    }

    public static CollectedTurn incomplete() {
        return new CollectedTurn(List.of(), List.of(), Map.of(), "", "", "", null,
                OptionalLong.empty(), OptionalLong.empty(), false);
    }
}
