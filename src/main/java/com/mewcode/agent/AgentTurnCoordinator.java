package com.mewcode.agent;

import com.mewcode.conversation.ContentBlock;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.TextBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolCall;
import com.mewcode.tool.ToolExecutor;
import com.mewcode.tool.ToolInvocationResult;
import com.mewcode.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** 编排一次模型请求、工具执行和一次结果回灌后的最终请求。 */
public final class AgentTurnCoordinator {

    private static final int QUEUE_CAPACITY = 512;

    private final LlmClient client;
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final ConversationManager conversation;
    private final ToolApiProtocol protocol;

    public AgentTurnCoordinator(LlmClient client,
                                ToolRegistry registry,
                                ToolExecutor executor,
                                ConversationManager conversation,
                                ToolApiProtocol protocol) {
        this.client = client;
        this.registry = registry;
        this.executor = executor;
        this.conversation = conversation;
        this.protocol = protocol;
    }

    public BlockingQueue<AgentEvent> start(String userText) {
        var queue = new LinkedBlockingQueue<AgentEvent>(QUEUE_CAPACITY);
        try {
            conversation.addUserMessage(userText);
            List<Map<String, Object>> schemas = registry.toAPIFormate(protocol);
            BlockingQueue<com.mewcode.llm.StreamEvent> firstStream =
                    client.stream(conversation, schemas);
            Thread.startVirtualThread(() -> runTurn(firstStream, queue));
        } catch (RuntimeException error) {
            put(queue, new AgentEvent.Error("Unable to start provider request."));
        }
        return queue;
    }

    public ConversationManager conversation() {
        return conversation;
    }

    private void runTurn(BlockingQueue<StreamEvent> firstStream,
                         BlockingQueue<AgentEvent> output) {
        try {
            Turn first = consume(firstStream, output);
            if (first.error() != null) {
                put(output, new AgentEvent.Error(first.error()));
                return;
            }
            if (first.calls().isEmpty()) {
                conversation.addAssistantMessage(first.blocks());
                put(output, new AgentEvent.Completed(first.stopReason()));
                return;
            }

            conversation.addAssistantMessage(first.blocks());
            List<ToolCall> executableCalls = new ArrayList<>();
            for (int i = 0; i < first.calls().size(); i++) {
                if (!first.parseErrors().containsKey(first.calls().get(i).toolUseId())) {
                    executableCalls.add(first.calls().get(i));
                }
            }
            List<ToolInvocationResult> executed = executor.executeBatch(executableCalls);
            List<ToolResultBlock> resultBlocks = ToolResultAssembler.assemble(
                    first.calls(), executed, first.parseErrors());
            conversation.addToolResults(resultBlocks);
            emitResults(output, first.calls(), resultBlocks, executed);

            // 最终答复阶段不再提供工具定义，确保本章不会开启第二轮工具执行。
            Turn finalTurn = consume(client.stream(conversation, List.of()), output);
            if (finalTurn.error() != null) {
                put(output, new AgentEvent.Error(finalTurn.error()));
                return;
            }
            if (!finalTurn.calls().isEmpty()) {
                put(output, new AgentEvent.Error(
                        "本章只支持一次工具结果回灌，未继续执行模型再次请求的工具调用。"));
                return;
            }
            conversation.addAssistantMessage(finalTurn.blocks());
            put(output, new AgentEvent.Completed(finalTurn.stopReason()));
        } catch (Exception error) {
            put(output, new AgentEvent.Error("Agent 回合执行失败："
                    + safeMessage(error) + "。"));
        }
    }

    private static Turn consume(BlockingQueue<StreamEvent> stream,
                                BlockingQueue<AgentEvent> output) throws InterruptedException {
        var blocks = new ArrayList<ContentBlock>();
        var calls = new ArrayList<ToolCall>();
        var parseErrors = new HashMap<String, String>();
        while (true) {
            StreamEvent event = stream.take();
            if (event instanceof StreamEvent.TextDelta text) {
                appendText(blocks, text.text());
                put(output, new AgentEvent.TextDelta(text.text()));
            } else if (event instanceof StreamEvent.ThinkingDelta thinking) {
                put(output, new AgentEvent.ThinkingDelta(thinking.text()));
            } else if (event instanceof StreamEvent.ToolCallComplete toolCall) {
                var call = new ToolCall(toolCall.toolUseId(), toolCall.toolName(), toolCall.arguments());
                calls.add(call);
                blocks.add(new ToolUseBlock(call.toolUseId(), call.toolName(), call.arguments()));
                put(output, new AgentEvent.ToolStarted(
                        call.toolUseId(), call.toolName(), call.arguments()));
            } else if (event instanceof StreamEvent.ToolCallParseError parseError) {
                String id = parseError.toolUseId() == null ? "invalid-tool-call" : parseError.toolUseId();
                var call = new ToolCall(id, parseError.toolName(), Map.of());
                calls.add(call);
                blocks.add(new ToolUseBlock(id, parseError.toolName(), Map.of()));
                parseErrors.put(id, parseError.message());
                put(output, new AgentEvent.ToolStarted(id, parseError.toolName(), Map.of()));
            } else if (event instanceof StreamEvent.Error error) {
                return new Turn(List.copyOf(blocks), List.copyOf(calls), parseErrors,
                        "", error.message());
            } else if (event instanceof StreamEvent.StreamEnd end) {
                return new Turn(List.copyOf(blocks), List.copyOf(calls), parseErrors,
                        end.stopReason(), null);
            }
        }
    }

    private static void emitResults(BlockingQueue<AgentEvent> output,
                                    List<ToolCall> calls,
                                    List<ToolResultBlock> blocks,
                                    List<ToolInvocationResult> executed) {
        var results = new HashMap<String, ToolResultBlock>();
        for (ToolResultBlock block : blocks) results.putIfAbsent(block.toolUseId(), block);
        for (ToolCall call : calls) {
            ToolResultBlock block = results.get(call.toolUseId());
            if (block != null) {
                var result = new com.mewcode.tool.ToolResult(block.content(), block.isError(), Map.of());
                for (ToolInvocationResult invocation : executed) {
                    if (invocation.toolUseId().equals(call.toolUseId())) {
                        result = invocation.result();
                        break;
                    }
                }
                put(output, new AgentEvent.ToolCompleted(call.toolUseId(), call.toolName(), result));
            }
        }
    }

    private static void appendText(List<ContentBlock> blocks, String text) {
        if (text == null || text.isEmpty()) return;
        if (!blocks.isEmpty() && blocks.getLast() instanceof TextBlock previous) {
            blocks.set(blocks.size() - 1, new TextBlock(previous.text() + text));
        } else {
            blocks.add(new TextBlock(text));
        }
    }

    private static void put(BlockingQueue<AgentEvent> queue, AgentEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record Turn(
            List<ContentBlock> blocks,
            List<ToolCall> calls,
            Map<String, String> parseErrors,
            String stopReason,
            String error) {
    }
}
