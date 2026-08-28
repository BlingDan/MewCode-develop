package com.mewcode.agent;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.LlmClient;
import com.mewcode.permission.BashSandbox;
import com.mewcode.permission.PathAuthorizationStore;
import com.mewcode.permission.PermissionContext;
import com.mewcode.permission.PermissionGate;
import com.mewcode.permission.PermissionMode;
import com.mewcode.permission.PermissionRuleEngine;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolCall;
import com.mewcode.tool.ToolExecutor;
import com.mewcode.tool.ToolInvocationResult;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

/**
 * 编排持续的 ReAct Agent Loop。
 *
 * <p>每一轮都遵循“调用 LLM → 收集完整响应 → 执行工具 → 回写工具结果”的顺序。 流式文本和工具状态通过 {@link AgentEventStream}
 * 对外发布，只有完整的一轮工具调用 和结果才会提交到会话历史，保证取消或 provider 出错时历史仍然合法。
 */
public final class AgentTurnCoordinator {

  private static final int QUEUE_CAPACITY = 512;

  private final LlmClient client;
  private final ToolRegistry registry;
  private final ToolExecutor executor;
  private final ConversationManager conversation;
  private final ToolApiProtocol protocol;
  private final AgentLoopConfig config;
  private final Function<AgentMode, String> systemPromptProvider;
  private final PromptRequestFactory promptRequestFactory;
  private final PermissionGate permissionGate;
  private final PermissionMode configuredPermissionMode;
  private final PermissionRuleEngine permissionRuleEngine;
  private final PathAuthorizationStore pathAuthorizationStore;
  private final BashSandbox bashSandbox;

  public AgentTurnCoordinator(
      LlmClient client,
      ToolRegistry registry,
      ToolExecutor executor,
      ConversationManager conversation,
      ToolApiProtocol protocol) {
    this(client, registry, executor, conversation, protocol, new AgentLoopConfig(), mode -> null);
  }

  public AgentTurnCoordinator(
      LlmClient client,
      ToolRegistry registry,
      ToolExecutor executor,
      ConversationManager conversation,
      ToolApiProtocol protocol,
      AgentLoopConfig config) {
    this(client, registry, executor, conversation, protocol, config, mode -> null);
  }

  public AgentTurnCoordinator(
      LlmClient client,
      ToolRegistry registry,
      ToolExecutor executor,
      ConversationManager conversation,
      ToolApiProtocol protocol,
      AgentLoopConfig config,
      Function<AgentMode, String> systemPromptProvider) {
    this(client, registry, executor, conversation, protocol, config, systemPromptProvider, null);
  }

  /** 使用结构化提示请求启动协调器；旧字符串构造器继续保留原有语义。 */
  public AgentTurnCoordinator(
      LlmClient client,
      ToolRegistry registry,
      ToolExecutor executor,
      ConversationManager conversation,
      ToolApiProtocol protocol,
      AgentLoopConfig config,
      PromptRequestFactory promptRequestFactory) {
    this(
        client,
        registry,
        executor,
        conversation,
        protocol,
        config,
        mode -> null,
        Objects.requireNonNull(promptRequestFactory, "promptRequestFactory"));
  }

  /** 创建启用五层权限系统的协调器。 */
  public AgentTurnCoordinator(
      LlmClient client,
      ToolRegistry registry,
      ToolExecutor executor,
      ConversationManager conversation,
      ToolApiProtocol protocol,
      AgentLoopConfig config,
      PromptRequestFactory promptRequestFactory,
      PermissionGate permissionGate,
      PermissionMode permissionMode,
      PermissionRuleEngine permissionRuleEngine,
      PathAuthorizationStore pathAuthorizationStore,
      BashSandbox bashSandbox) {
    this(
        client,
        registry,
        executor,
        conversation,
        protocol,
        config,
        mode -> null,
        Objects.requireNonNull(promptRequestFactory, "promptRequestFactory"),
        Objects.requireNonNull(permissionGate, "permissionGate"),
        Objects.requireNonNull(permissionMode, "permissionMode"),
        Objects.requireNonNull(permissionRuleEngine, "permissionRuleEngine"),
        Objects.requireNonNull(pathAuthorizationStore, "pathAuthorizationStore"),
        Objects.requireNonNull(bashSandbox, "bashSandbox"));
  }

  private AgentTurnCoordinator(
      LlmClient client,
      ToolRegistry registry,
      ToolExecutor executor,
      ConversationManager conversation,
      ToolApiProtocol protocol,
      AgentLoopConfig config,
      Function<AgentMode, String> systemPromptProvider,
      PromptRequestFactory promptRequestFactory) {
    this(
        client,
        registry,
        executor,
        conversation,
        protocol,
        config,
        systemPromptProvider,
        promptRequestFactory,
        null,
        null,
        null,
        null,
        null);
  }

  private AgentTurnCoordinator(
      LlmClient client,
      ToolRegistry registry,
      ToolExecutor executor,
      ConversationManager conversation,
      ToolApiProtocol protocol,
      AgentLoopConfig config,
      Function<AgentMode, String> systemPromptProvider,
      PromptRequestFactory promptRequestFactory,
      PermissionGate permissionGate,
      PermissionMode permissionMode,
      PermissionRuleEngine permissionRuleEngine,
      PathAuthorizationStore pathAuthorizationStore,
      BashSandbox bashSandbox) {
    this.client = Objects.requireNonNull(client, "client");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.conversation = Objects.requireNonNull(conversation, "conversation");
    this.protocol = Objects.requireNonNull(protocol, "protocol");
    this.config = Objects.requireNonNull(config, "config").copy();
    this.config.validate();
    this.systemPromptProvider =
        Objects.requireNonNull(systemPromptProvider, "systemPromptProvider");
    this.promptRequestFactory = promptRequestFactory;
    this.permissionGate = permissionGate;
    this.configuredPermissionMode = permissionMode;
    this.permissionRuleEngine = permissionRuleEngine;
    this.pathAuthorizationStore = pathAuthorizationStore;
    this.bashSandbox = bashSandbox;
  }

  /**
   * 兼容现有 TUI/测试的队列入口；新代码应使用 {@link #startRun(String, AgentMode)}。 这个桥接入口只负责把新的事件流转成旧的阻塞队列，不改变 Loop
   * 语义。
   */
  public BlockingQueue<AgentEvent> start(String userText) {
    AgentRun run = startRun(userText, AgentMode.EXECUTE);
    var queue = new LinkedBlockingQueue<AgentEvent>(QUEUE_CAPACITY);
    Thread.startVirtualThread(() -> bridge(run.events(), queue));
    return queue;
  }

  /**
   * 启动一次独立的异步运行。
   *
   * <p>用户消息在启动工作线程前写入历史，模式在本次运行开始时固定；返回的 {@link AgentRun} 同时提供事件流和取消句柄。工作线程只负责 Loop，不阻塞 TUI
   * 的消息处理线程。
   */
  public AgentRun startRun(String userText, AgentMode mode) {
    Objects.requireNonNull(userText, "userText");
    AgentMode effectiveMode = mode == null ? AgentMode.EXECUTE : mode;
    var run = new AgentRun();
    run.setPermissionPublisher(
        request -> run.events().publish(new AgentEvent.PermissionRequested(request)));
    try {
      conversation.addUserMessage(userText);
      Thread.startVirtualThread(() -> runLoop(run, effectiveMode));
    } catch (Throwable error) {
      finish(run, 0, "Agent Loop 启动失败：" + safeMessage(error), AgentEvent.ErrorCategory.LOOP);
    }
    return run;
  }

  /** 返回本次协调器持有的会话历史，供继续对话和测试读取。 */
  public ConversationManager conversation() {
    return conversation;
  }

  /** 返回配置副本，避免调用方在运行中修改迭代上限。 */
  public AgentLoopConfig config() {
    return config.copy();
  }

  /**
   * 执行 ReAct 主循环。
   *
   * <p>收到无工具的完整 assistant 响应即正常结束；达到迭代上限、连续未知工具、 取消或异常也都从这里统一收口。工具结果按模型原始调用顺序回写，即使安全工具
   * 在后台并发执行，也不会改变下一轮看到的消息顺序。
   */
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
        PermissionContext permissions = createPermissionContext(run, mode);
        List<String> deferredToolNames = registry.deferredToolNames();
        List<Map<String, Object>> schemas =
            permissionGate == null
                ? registry.toAPIFormateForModel(protocol, policy::isAllowed)
                : registry.toAPIFormateForModel(protocol, null);
        CancellableLlmStream stream;
        if (promptRequestFactory != null) {
          var request =
              promptRequestFactory.create(
                  mode,
                  round,
                  round == 1,
                  conversation.getMessages(),
                  schemas,
                  deferredToolNames);
          stream = client.openStream(request);
        } else {
          String systemPrompt = systemPromptProvider.apply(mode);
          stream =
              systemPrompt == null
                  ? client.openStream(conversation, schemas)
                  : client.openStream(conversation, schemas, systemPrompt);
        }
        CollectedTurn turn = collector.collect(run, stream, round);

        if (run.cancellationToken().isCancelled()) {
          finish(run, completedRounds, null, null);
          return;
        }
        if (!turn.complete()) {
          finish(
              run,
              completedRounds,
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

        List<ToolCall> executableCalls =
            turn.calls().stream()
                .filter(call -> !turn.parseErrors().containsKey(call.toolUseId()))
                .toList();
        List<ToolInvocationResult> executed =
            permissionGate == null
                ? executor.executeBatch(executableCalls, policy, run.cancellationToken())
                : executor.executeBatch(executableCalls, permissions);
        List<ToolResultBlock> resultBlocks =
            ToolResultAssembler.assemble(turn.calls(), executed, turn.parseErrors());
        // 工具调用和结果必须成对提交，取消发生在工具执行期间也不能留下悬空 assistant 消息。
        conversation.addToolTurn(turn.blocks(), resultBlocks);
        emitResults(run, turn.calls(), resultBlocks, executed);

        if (run.cancellationToken().isCancelled()) {
          finish(run, completedRounds, null, null);
          return;
        }

        boolean hasExecutableKnownTool =
            permissionGate == null
                ? executableCalls.stream()
                    .anyMatch(
                        call -> registry.get(call.toolName()).filter(policy::isAllowed).isPresent())
                : executableCalls.stream()
                    .anyMatch(call -> registry.get(call.toolName()).isPresent());
        unknownToolRounds = hasExecutableKnownTool ? 0 : unknownToolRounds + 1;
        if (unknownToolRounds >= config.getUnknownToolRoundLimit()) {
          finish(
              run,
              completedRounds,
              "连续 " + config.getUnknownToolRoundLimit() + " 轮没有可执行的已知工具，Agent Loop 已停止。",
              AgentEvent.ErrorCategory.LOOP);
          return;
        }
        if (completedRounds >= config.getMaxIterations()) {
          finish(
              run,
              completedRounds,
              "已达到最大迭代次数 " + config.getMaxIterations() + "，Agent Loop 已停止。",
              AgentEvent.ErrorCategory.LOOP);
          return;
        }
      }

      if (run.cancellationToken().isCancelled()) {
        finish(run, completedRounds, null, null);
      } else {
        finish(
            run,
            completedRounds,
            "已达到最大迭代次数 " + config.getMaxIterations() + "，Agent Loop 已停止。",
            AgentEvent.ErrorCategory.LOOP);
      }
    } catch (InterruptedException error) {
      if (!run.cancellationToken().isCancelled()) {
        Thread.currentThread().interrupt();
        finish(run, completedRounds, "Agent Loop 被线程中断。", AgentEvent.ErrorCategory.LOOP);
      } else {
        finish(run, completedRounds, null, null);
      }
    } catch (Throwable error) {
      if (run.cancellationToken().isCancelled()) {
        finish(run, completedRounds, null, null);
      } else {
        finish(
            run,
            completedRounds,
            "Agent Loop 执行失败：" + safeMessage(error),
            AgentEvent.ErrorCategory.LOOP);
      }
    }
  }

  private PermissionContext createPermissionContext(AgentRun run, AgentMode mode) {
    if (permissionGate == null) return null;
    PermissionMode effectiveMode =
        mode == AgentMode.PLAN ? PermissionMode.PLAN : configuredPermissionMode;
    return new PermissionContext(
        executor.projectRoot(),
        effectiveMode,
        permissionRuleEngine,
        pathAuthorizationStore,
        bashSandbox,
        run.permissionBroker(),
        run.cancellationToken());
  }

  /** 将工具结果事件恢复为模型调用顺序，避免并发执行导致 UI 顺序抖动。 */
  private static void emitResults(
      AgentRun run,
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
      ToolInvocationResult invocation = invocations == null ? null : invocations.pollFirst();
      ToolResult result =
          invocation == null
              ? new ToolResult(block.content(), block.isError(), Map.of())
              : invocation.result();
      run.events()
          .publish(
              new AgentEvent.ToolResult(
                  call.toolUseId(),
                  call.toolName(),
                  result.content(),
                  result.isError(),
                  durationMillis(result)));
    }
  }

  private static long durationMillis(ToolResult result) {
    Object value = result.metadata().get("durationMs");
    return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
  }

  /** 统一发布错误、循环结束并关闭事件流；调用可重复但事件收口由 AgentRun 保证。 */
  private static void finish(
      AgentRun run, int totalRounds, String errorMessage, AgentEvent.ErrorCategory category) {
    if (errorMessage != null && category != null) {
      run.events().publish(new AgentEvent.Error(errorMessage, category));
    }
    run.events().publish(new AgentEvent.LoopComplete(totalRounds));
    run.complete();
  }

  /** 将新事件流桥接到旧 API 的阻塞队列，供历史调用方平滑迁移。 */
  private static void bridge(AgentEventStream source, BlockingQueue<AgentEvent> target) {
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
    return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
  }
}
