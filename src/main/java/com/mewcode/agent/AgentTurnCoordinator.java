package com.mewcode.agent;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.LlmClient;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolCall;
import com.mewcode.tool.ToolExecutor;
import com.mewcode.tool.ToolInvocationResult;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

/** 编排持续的 ReAct Agent Loop。 */
public final class AgentTurnCoordinator {

    private static final int QUEUE_CAPACITY = 512;

    private final LlmClient client;
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final ConversationManager conversation;
    private final ToolApiProtocol protocol;
    private final AgentLoopConfig config;
    private final Function<AgentMode, String> systemPromptProvider;

    public AgentTurnCoordinator(LlmClient client,
                                ToolRegistry registry,
                                ToolExecutor executor,
                                ConversationManager conversation,
                                ToolApiProtocol protocol) {
        this(client, registry, executor, conversation, protocol,
                new AgentLoopConfig(), mode -> null);
    }

    public AgentTurnCoordinator(LlmClient client,
                                ToolRegistry registry,
                                ToolExecutor executor,
                                ConversationManager conversation,
                                ToolApiProtocol protocol,
                                AgentLoopConfig config) {
        this(client, registry, executor, conversation, protocol, config, mode -> null);
    }

    public AgentTurnCoordinator(LlmClient client,
                                ToolRegistry registry,
                                ToolExecutor executor,
                                ConversationManager conversation,
                                ToolApiProtocol protocol,
                                AgentLoopConfig config,
                                Function<AgentMode, String> systemPromptProvider) {
        this.client = Objects.requireNonNull(client, "client");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.conversation = Objects.requireNonNull(conversation, "conversation");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.config = Objects.requireNonNull(config, "config").copy();
        this.config.validate();
        this.systemPromptProvider = Objects.requireNonNull(systemPromptProvider,
                "systemPromptProvider");
    }

    /** 兼容现有 TUI/测试的队列入口；新代码应使用 startRun。 */
    public BlockingQueue<AgentEvent> start(String userText) {
        AgentRun run = startRun(userText, AgentMode.EXECUTE);
        var queue = new LinkedBlockingQueue<AgentEvent>(QUEUE_CAPACITY);
        Thread.startVirtualThread(() -> bridge(run.events(), queue));
        return queue;
    }

    public AgentRun startRun(String userText, AgentMode mode) {
        Objects.requireNonNull(userText, "userText");
        AgentMode effectiveMode = mode == null ? AgentMode.EXECUTE : mode;
        var run = new AgentRun();
        try {
            conversation.addUserMessage(userText);
            Thread.startVirtualThread(() -> runLoop(run, effectiveMode));
        } catch (Throwable error) {
            finish(run, 0, "Agent Loop 启动失败：" + safeMessage(error),
                    AgentEvent.ErrorCategory.LOOP);
        }
        return run;
    }

    public ConversationManager conversation() {
        return conversation;
    }

    public AgentLoopConfig config() {
        return config.copy();
    }

    private void runLoop(AgentRun run, AgentMode mode) {
        var usage = new TokenUsageAccumulator();
        var collector = new TurnStreamCollector(usage);
        var policy = ToolPolicy.forMode(mode);
        int completedRounds = 0;
        int unknownToolRounds = 0;

        try {
            while (!run.cancellationToken().isCancelled()
                    && completedRounds < config.getMaxIterations()) {
                int round = completedRounds + 1;
                List<Map<String, Object>> schemas = registry.toAPIFormate(
                        protocol, policy::isAllowed);
                String systemPrompt = systemPromptProvider.apply(mode);
                CancellableLlmStream stream = systemPrompt == null
                        ? client.openStream(conversation, schemas)
                        : client.openStream(conversation, schemas, systemPrompt);
                CollectedTurn turn = collector.collect(run, stream, round);

                if (run.cancellationToken().isCancelled()) {
                    finish(run, completedRounds, null, null);
                    return;
                }
                if (!turn.complete()) {
                    finish(run, completedRounds,
                            turn.error() == null ? "LLM 流未完整结束。" : turn.error(),
                            AgentEvent.ErrorCategory.PROVIDER);
                    return;
                }

                completedRounds = round;
                run.events().publish(new AgentEvent.TurnComplete(round));
                if (turn.calls().isEmpty()) {
                    conversation.addAssistantMessage(turn.blocks());
                    finish(run, completedRounds, null, null);
                    return;
                }

                List<ToolCall> executableCalls = turn.calls().stream()
                        .filter(call -> !turn.parseErrors().containsKey(call.toolUseId()))
                        .toList();
                List<ToolInvocationResult> executed = executor.executeBatch(
                        executableCalls, policy, run.cancellationToken());
                List<ToolResultBlock> resultBlocks = ToolResultAssembler.assemble(
                        turn.calls(), executed, turn.parseErrors());
                // 工具调用和结果必须成对提交，取消发生在工具执行期间也不能留下悬空 assistant 消息。
                conversation.addToolTurn(turn.blocks(), resultBlocks);
                emitResults(run, turn.calls(), resultBlocks, executed);

                if (run.cancellationToken().isCancelled()) {
                    finish(run, completedRounds, null, null);
                    return;
                }

                boolean hasExecutableKnownTool = executableCalls.stream()
                        .anyMatch(call -> registry.get(call.toolName())
                                .filter(policy::isAllowed)
                                .isPresent());
                unknownToolRounds = hasExecutableKnownTool ? 0 : unknownToolRounds + 1;
                if (unknownToolRounds >= config.getUnknownToolRoundLimit()) {
                    finish(run, completedRounds,
                            "连续 " + config.getUnknownToolRoundLimit()
                                    + " 轮没有可执行的已知工具，Agent Loop 已停止。",
                            AgentEvent.ErrorCategory.LOOP);
                    return;
                }
                if (completedRounds >= config.getMaxIterations()) {
                    finish(run, completedRounds,
                            "已达到最大迭代次数 " + config.getMaxIterations()
                                    + "，Agent Loop 已停止。",
                            AgentEvent.ErrorCategory.LOOP);
                    return;
                }
            }

            if (run.cancellationToken().isCancelled()) {
                finish(run, completedRounds, null, null);
            } else {
                finish(run, completedRounds,
                        "已达到最大迭代次数 " + config.getMaxIterations()
                                + "，Agent Loop 已停止。",
                        AgentEvent.ErrorCategory.LOOP);
            }
        } catch (InterruptedException error) {
            if (!run.cancellationToken().isCancelled()) {
                Thread.currentThread().interrupt();
                finish(run, completedRounds, "Agent Loop 被线程中断。",
                        AgentEvent.ErrorCategory.LOOP);
            } else {
                finish(run, completedRounds, null, null);
            }
        } catch (Throwable error) {
            if (run.cancellationToken().isCancelled()) {
                finish(run, completedRounds, null, null);
            } else {
                finish(run, completedRounds, "Agent Loop 执行失败：" + safeMessage(error),
                        AgentEvent.ErrorCategory.LOOP);
            }
        }
    }

    private static void emitResults(AgentRun run,
                                    List<ToolCall> calls,
                                    List<ToolResultBlock> blocks,
                                    List<ToolInvocationResult> executed) {
        var byId = new java.util.HashMap<String, ArrayDeque<ToolInvocationResult>>();
        for (ToolInvocationResult invocation : executed) {
            byId.computeIfAbsent(invocation.toolUseId(), ignored -> new ArrayDeque<>())
                    .addLast(invocation);
        }
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            ToolResultBlock block = blocks.get(i);
            var invocations = byId.get(call.toolUseId());
            ToolInvocationResult invocation = invocations == null
                    ? null : invocations.pollFirst();
            ToolResult result = invocation == null
                    ? new ToolResult(block.content(), block.isError(), Map.of())
                    : invocation.result();
            run.events().publish(new AgentEvent.ToolResult(
                    call.toolUseId(), call.toolName(), result.content(), result.isError(),
                    durationMillis(result)));
        }
    }

    private static long durationMillis(ToolResult result) {
        Object value = result.metadata().get("durationMs");
        return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }

    private static void finish(AgentRun run,
                               int totalRounds,
                               String errorMessage,
                               AgentEvent.ErrorCategory category) {
        if (errorMessage != null && category != null) {
            run.events().publish(new AgentEvent.Error(errorMessage, category));
        }
        run.events().publish(new AgentEvent.LoopComplete(totalRounds));
        run.complete();
    }

    private static void bridge(AgentEventStream source,
                               BlockingQueue<AgentEvent> target) {
        try {
            while (true) {
                AgentEvent event = source.next();
                if (event == null) return;
                target.put(event);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }
}
