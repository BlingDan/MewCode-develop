package com.mewcode.agent;

import com.mewcode.conversation.ContentBlock;
import com.mewcode.conversation.TextBlock;
import com.mewcode.conversation.ThinkingBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.StreamEvent;
import com.mewcode.tool.ToolCall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** 一边发布增量事件，一边收集一轮完整 provider 响应。 */
public final class TurnStreamCollector {

    private final TokenUsageAccumulator usage;

    public TurnStreamCollector(TokenUsageAccumulator usage) {
        this.usage = usage;
    }

    public CollectedTurn collect(AgentRun run,
                                 CancellableLlmStream stream,
                                 int round) throws InterruptedException {
        var blocks = new ArrayList<ContentBlock>();
        var calls = new ArrayList<ToolCall>();
        var parseErrors = new HashMap<String, String>();
        var text = new StringBuilder();
        var thinking = new StringBuilder();
        String stopReason = "";
        boolean usageSeen = false;
        Runnable closeStream = stream::close;
        run.addCancellationHook(closeStream);
        try {
            while (true) {
                if (run.cancellationToken().isCancelled()) return CollectedTurn.incomplete();
                StreamEvent event = stream.next();
                if (event == null) {
                    if (!usageSeen) {
                        usage.updateRound(round, OptionalLong.empty(), OptionalLong.empty());
                        publishUsage(run);
                    }
                    return new CollectedTurn(blocks, calls, parseErrors, text.toString(),
                            thinking.toString(), stopReason, "LLM 流提前结束。",
                            usage.inputTokens(), usage.outputTokens(), false);
                }
                if (event instanceof StreamEvent.TextDelta delta) {
                    text.append(delta.text());
                    appendText(blocks, delta.text());
                    run.events().publish(new AgentEvent.StreamText(delta.text()));
                } else if (event instanceof StreamEvent.ThinkingDelta delta) {
                    thinking.append(delta.text());
                    appendThinking(blocks, thinking.toString(), delta.signature());
                } else if (event instanceof StreamEvent.Usage tokenUsage) {
                    usageSeen = true;
                    usage.updateRound(round, tokenUsage.inputTokens(), tokenUsage.outputTokens());
                    publishUsage(run);
                } else if (event instanceof StreamEvent.ToolCallComplete callEvent) {
                    String id = nonBlank(callEvent.toolUseId(), "tool-call-" + calls.size());
                    String name = nonBlank(callEvent.toolName(), "unknown_tool");
                    var call = new ToolCall(id, name, callEvent.arguments());
                    calls.add(call);
                    blocks.add(new ToolUseBlock(id, name, call.arguments()));
                    run.events().publish(new AgentEvent.ToolUse(id, name, call.arguments()));
                } else if (event instanceof StreamEvent.ToolCallParseError parseError) {
                    String id = nonBlank(parseError.toolUseId(), "invalid-tool-call-" + calls.size());
                    String name = nonBlank(parseError.toolName(), "unknown_tool");
                    var call = new ToolCall(id, name, Map.of());
                    calls.add(call);
                    blocks.add(new ToolUseBlock(id, name, Map.of()));
                    parseErrors.put(id, parseError.message());
                    run.events().publish(new AgentEvent.ToolUse(id, name, Map.of()));
                } else if (event instanceof StreamEvent.Error error) {
                    if (!usageSeen) {
                        usage.updateRound(round, OptionalLong.empty(), OptionalLong.empty());
                        publishUsage(run);
                    }
                    return new CollectedTurn(blocks, calls, parseErrors, text.toString(),
                            thinking.toString(), stopReason, error.message(),
                            usage.inputTokens(), usage.outputTokens(), false);
                } else if (event instanceof StreamEvent.StreamEnd end) {
                    if (!usageSeen) {
                        usage.updateRound(round, OptionalLong.empty(), OptionalLong.empty());
                        publishUsage(run);
                    }
                    stopReason = end.stopReason();
                    return new CollectedTurn(blocks, calls, parseErrors, text.toString(),
                            thinking.toString(), stopReason, null,
                            usage.inputTokens(), usage.outputTokens(), true);
                }
            }
        } finally {
            run.removeCancellationHook(closeStream);
        }
    }

    private void publishUsage(AgentRun run) {
        run.events().publish(new AgentEvent.Usage(usage.inputTokens(), usage.outputTokens()));
    }

    private static void appendText(List<ContentBlock> blocks, String value) {
        if (value == null || value.isEmpty()) return;
        if (!blocks.isEmpty() && blocks.getLast() instanceof TextBlock previous) {
            blocks.set(blocks.size() - 1, new TextBlock(previous.text() + value));
        } else {
            blocks.add(new TextBlock(value));
        }
    }

    private static void appendThinking(List<ContentBlock> blocks,
                                       String value,
                                       String signature) {
        if (!blocks.isEmpty() && blocks.getLast() instanceof ThinkingBlock previous) {
            String effectiveSignature = signature.isBlank()
                    ? previous.signature()
                    : signature;
            blocks.set(blocks.size() - 1, new ThinkingBlock(value, effectiveSignature));
            return;
        }
        blocks.add(new ThinkingBlock(value, signature));
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
