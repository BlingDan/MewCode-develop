package com.mewcode.agent;

import com.mewcode.compact.ContextException;
import com.mewcode.compact.ContextManager;
import com.mewcode.compact.ContextPreparation;
import com.mewcode.compact.ContextRequest;
import com.mewcode.compact.ContextTrigger;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.hook.HookEngine;
import com.mewcode.hook.HookEvent;
import com.mewcode.hook.HookInvocation;
import com.mewcode.hook.HookRejection;
import com.mewcode.hook.HookSessionState;
import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.PromptRequest;
import com.mewcode.llm.StreamEvent;
import com.mewcode.permission.BashSandbox;
import com.mewcode.permission.PathAuthorizationStore;
import com.mewcode.permission.PermissionContext;
import com.mewcode.permission.PermissionGate;
import com.mewcode.permission.PermissionMode;
import com.mewcode.permission.PermissionRuleEngine;
import com.mewcode.permission.PermissionRuntime;
import com.mewcode.skill.ProviderRouter;
import com.mewcode.skill.SkillCatalog;
import com.mewcode.skill.SkillDefinition;
import com.mewcode.skill.SkillExecutor;
import com.mewcode.skill.SkillRun;
import com.mewcode.subagent.SubAgentRuntime;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolCall;
import com.mewcode.tool.ToolExecutor;
import com.mewcode.tool.ToolInvocationResult;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import com.mewcode.tool.impl.AgentTool;
import com.mewcode.tool.impl.LoadSkillTool;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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
  private final ContextManager contextManager;
  private final PermissionGate permissionGate;
  private final PermissionMode configuredPermissionMode;
  private final PermissionRuleEngine permissionRuleEngine;
  private PermissionRuntime permissionRuntime;
  private final PathAuthorizationStore pathAuthorizationStore;
  private final BashSandbox bashSandbox;
  private volatile Supplier<PromptAdditions> promptAdditionsSupplier = PromptAdditions::empty;
  private volatile Supplier<ToolPolicy> toolPolicySupplier;
  private volatile Consumer<List<Message>> completionListener = ignored -> {};
  private volatile SkillCatalog skillCatalog;
  private volatile Supplier<SkillCatalog.RefreshResult> skillRefresher;
  private volatile ProviderRouter providerRouter;
  private volatile Function<SkillExecutor.ForkRequest, ToolResult> forkRunner;
  private volatile HookEngine hookEngine;
  private volatile HookSessionState hookState;
  private volatile String hookSessionId;
  private volatile SubAgentRuntime subAgentRuntime;

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

  /** 使用结构化提示请求和上下文管理器启动协调器。 */
  public AgentTurnCoordinator(
      LlmClient client,
      ToolRegistry registry,
      ToolExecutor executor,
      ConversationManager conversation,
      ToolApiProtocol protocol,
      AgentLoopConfig config,
      PromptRequestFactory promptRequestFactory,
      ContextManager contextManager) {
    this(
        client,
        registry,
        executor,
        conversation,
        protocol,
        config,
        mode -> null,
        Objects.requireNonNull(promptRequestFactory, "promptRequestFactory"),
        Objects.requireNonNull(contextManager, "contextManager"),
        null,
        null,
        null,
        null,
        null);
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
        null,
        Objects.requireNonNull(permissionGate, "permissionGate"),
        Objects.requireNonNull(permissionMode, "permissionMode"),
        Objects.requireNonNull(permissionRuleEngine, "permissionRuleEngine"),
        Objects.requireNonNull(pathAuthorizationStore, "pathAuthorizationStore"),
        Objects.requireNonNull(bashSandbox, "bashSandbox"));
  }

  /** 创建使用可变运行期权限、但按 Agent Run 固定快照的协调器。 */
  public AgentTurnCoordinator(
      LlmClient client,
      ToolRegistry registry,
      ToolExecutor executor,
      ConversationManager conversation,
      ToolApiProtocol protocol,
      AgentLoopConfig config,
      PromptRequestFactory promptRequestFactory,
      ContextManager contextManager,
      PermissionGate permissionGate,
      PermissionRuntime permissionRuntime,
      PathAuthorizationStore pathAuthorizationStore,
      BashSandbox bashSandbox) {
    this(
        client,
        registry,
        executor,
        conversation,
        protocol,
        config,
        promptRequestFactory,
        contextManager,
        permissionGate,
        Objects.requireNonNull(permissionRuntime, "permissionRuntime").mode(),
        permissionRuntime.snapshot().ruleEngine(),
        pathAuthorizationStore,
        bashSandbox);
    this.permissionRuntime = permissionRuntime;
  }

  /** 创建同时启用上下文管理和五层权限系统的协调器。 */
  public AgentTurnCoordinator(
      LlmClient client,
      ToolRegistry registry,
      ToolExecutor executor,
      ConversationManager conversation,
      ToolApiProtocol protocol,
      AgentLoopConfig config,
      PromptRequestFactory promptRequestFactory,
      ContextManager contextManager,
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
        Objects.requireNonNull(contextManager, "contextManager"),
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
      ContextManager contextManager,
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
    this.contextManager = contextManager;
    this.permissionGate = permissionGate;
    this.configuredPermissionMode = permissionMode;
    this.permissionRuleEngine = permissionRuleEngine;
    this.permissionRuntime =
        permissionMode == null || permissionRuleEngine == null
            ? null
            : new PermissionRuntime(permissionMode, permissionRuleEngine);
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
    return startRun(userText, mode, new SkillRun());
  }

  /** 启动带预激活 Skill 的请求；主要供动态斜杠命令使用。 */
  public AgentRun startRun(String userText, AgentMode mode, SkillRun skills) {
    Objects.requireNonNull(userText, "userText");
    SkillRun runSkills = Objects.requireNonNull(skills, "skills");
    AgentMode effectiveMode = mode == null ? AgentMode.EXECUTE : mode;
    var run = new AgentRun();
    run.setPermissionPublisher(
        request -> run.events().publish(new AgentEvent.PermissionRequested(request)));
    try {
      int startingMessageCount = conversation.getMessages().size();
      PermissionRuntime.Snapshot permissionSnapshot =
          permissionRuntime == null ? null : permissionRuntime.snapshot();
      conversation.addUserMessage(userText);
      Thread.startVirtualThread(
          () ->
              runLoop(
                  run,
                  effectiveMode,
                  startingMessageCount,
                  userText,
                  permissionSnapshot,
                  runSkills,
                  UUID.randomUUID().toString()));
    } catch (Throwable error) {
      finish(run, 0, "Agent Loop 启动失败：" + safeMessage(error), AgentEvent.ErrorCategory.LOOP);
    }
    return run;
  }

  /** 启动一次不增加用户消息、不计入 Agent 轮次的手动上下文压缩。 */
  public AgentRun startManualCompaction(AgentMode mode) {
    return startManualCompaction(mode, "");
  }

  /** 启动带可选保留重点的手动压缩。 */
  public AgentRun startManualCompaction(AgentMode mode, String focus) {
    AgentMode effectiveMode = mode == null ? AgentMode.EXECUTE : mode;
    var run = new AgentRun();
    run.setPermissionPublisher(
        request -> run.events().publish(new AgentEvent.PermissionRequested(request)));
    if (contextManager == null) {
      finish(run, 0, "上下文管理未初始化。", AgentEvent.ErrorCategory.CONTEXT);
      return run;
    }
    try {
      Thread.startVirtualThread(() -> manualCompactionLoop(run, effectiveMode, focus));
    } catch (Throwable error) {
      finish(run, 0, "手动上下文压缩启动失败：" + safeMessage(error), AgentEvent.ErrorCategory.CONTEXT);
    }
    return run;
  }

  /** 返回手动压缩请求的当前 Token 估算，不触发 Provider。 */
  public long estimateManualCompactionTokens(AgentMode mode) {
    if (contextManager == null) return 0;
    AgentMode effectiveMode = mode == null ? AgentMode.EXECUTE : mode;
    ContextRequest request =
        promptRequestFactory == null
            ? contextRequestFromLegacyPrompt(effectiveMode)
            : promptRequestFactory.createContextRequest(
                effectiveMode, 1, true, List.of(), List.of());
    return contextManager.estimateTokens(conversation, request);
  }

  private void manualCompactionLoop(AgentRun run, AgentMode mode, String focus) {
    Thread currentThread = Thread.currentThread();
    Runnable interruptThread = currentThread::interrupt;
    run.addCancellationHook(interruptThread);
    try {
      ContextRequest request =
          promptRequestFactory == null
              ? contextRequestFromLegacyPrompt(mode)
              : promptRequestFactory.createContextRequest(mode, 1, true, List.of(), List.of());
      run.events().publish(new AgentEvent.CompactionStarted(ContextTrigger.MANUAL));
      var result = contextManager.forceCompact(conversation, request, ContextTrigger.MANUAL, focus);
      run.events().publish(new AgentEvent.CompactionComplete(result));
      dispatchCompact(mode, ContextTrigger.MANUAL, result, run);
      finish(run, 0, null, null);
    } catch (ContextException error) {
      if (run.cancellationToken().isCancelled()) {
        finish(run, 0, null, null);
      } else {
        finish(run, 0, "上下文管理失败：" + safeMessage(error), AgentEvent.ErrorCategory.CONTEXT);
      }
    } catch (Throwable error) {
      if (run.cancellationToken().isCancelled()) {
        finish(run, 0, null, null);
      } else {
        finish(run, 0, "手动上下文压缩失败：" + safeMessage(error), AgentEvent.ErrorCategory.CONTEXT);
      }
    } finally {
      run.removeCancellationHook(interruptThread);
    }
  }

  private ContextRequest contextRequestFromLegacyPrompt(AgentMode mode) {
    String prompt = systemPromptProvider.apply(mode);
    return new ContextRequest(
        prompt == null ? List.of() : List.of(prompt), List.of(), Optional.empty());
  }

  /** 返回本次协调器持有的会话历史，供继续对话和测试读取。 */
  public ConversationManager conversation() {
    return conversation;
  }

  /** 返回配置副本，避免调用方在运行中修改迭代上限。 */
  public AgentLoopConfig config() {
    return config.copy();
  }

  /** 设置每轮请求前读取的 memory/恢复提醒快照。 */
  public void setPromptAdditionsSupplier(Supplier<PromptAdditions> supplier) {
    promptAdditionsSupplier = Objects.requireNonNull(supplier, "supplier");
  }

  /** 设置子运行时使用的绝对工具策略；未设置时沿用本轮模式和 Skill 策略。 */
  public void setToolPolicySupplier(Supplier<ToolPolicy> supplier) {
    toolPolicySupplier = Objects.requireNonNull(supplier, "supplier");
  }

  /** 绑定 SubAgent 工具的实际运行器；未绑定时 Agent 调用会返回可见错误。 */
  public void setSubAgentRuntime(SubAgentRuntime runtime) {
    subAgentRuntime = Objects.requireNonNull(runtime, "runtime");
  }

  /** 设置自然完成一轮后的异步通知；通知失败不会改变 Agent Loop 结果。 */
  public void setCompletionListener(Consumer<List<Message>> listener) {
    completionListener = Objects.requireNonNull(listener, "listener");
  }

  /** 注入可选 Skill 运行依赖；旧调用方不配置时保持原行为。 */
  public void configureSkills(
      SkillCatalog catalog,
      Supplier<SkillCatalog.RefreshResult> refresher,
      ProviderRouter router,
      Function<SkillExecutor.ForkRequest, ToolResult> forkRunner) {
    this.skillCatalog = Objects.requireNonNull(catalog, "catalog");
    this.skillRefresher = Objects.requireNonNull(refresher, "refresher");
    this.providerRouter = router;
    this.forkRunner = forkRunner;
  }

  /** 绑定当前会话的 Hook 引擎和内存状态；未绑定时保持旧行为。 */
  public void configureHooks(HookEngine engine, HookSessionState state) {
    this.hookEngine = Objects.requireNonNull(engine, "engine");
    this.hookState = Objects.requireNonNull(state, "state");
    executor.configureHooks(engine, state);
    if (hookSessionId != null) executor.setHookSessionId(hookSessionId);
  }

  /** 更新事件上下文中的会话标识，不会重置 Hook 状态。 */
  public void setHookSessionId(String sessionId) {
    this.hookSessionId = sessionId == null || sessionId.isBlank() ? null : sessionId;
    executor.setHookSessionId(this.hookSessionId);
  }

  /**
   * 执行 ReAct 主循环。
   *
   * <p>收到无工具的完整 assistant 响应即正常结束；达到迭代上限、连续未知工具、 取消或异常也都从这里统一收口。工具结果按模型原始调用顺序回写，即使安全工具
   * 在后台并发执行，也不会改变下一轮看到的消息顺序。
   */
  private void runLoop(
      AgentRun run,
      AgentMode mode,
      int startingMessageCount,
      String userText,
      PermissionRuntime.Snapshot permissionSnapshot,
      SkillRun skills,
      String requestId) {
    var usage = new TokenUsageAccumulator();
    var collector = new TurnStreamCollector(usage);
    boolean memoryOnlyRequest = isMemoryOnlyRequest(userText);
    int completedRounds = 0;
    int unknownToolRounds = 0;
    int emergencyRecoveryRound = -1;
    Thread currentThread = Thread.currentThread();
    Runnable interruptThread = currentThread::interrupt;
    run.addCancellationHook(interruptThread);

    try {
      dispatchHook(
          HookEvent.TURN_START,
          Map.of("prompt", userText, "request_id", requestId, "mode", mode.name()),
          run);
      while (!run.cancellationToken().isCancelled()
          && completedRounds < config.getMaxIterations()) {
        int round = completedRounds + 1;
        ToolPolicy policy =
            toolPolicySupplier == null
                ? ToolPolicy.forModeAndTools(
                    mode, skills.allowedTools(), !skills.activeSkills().isEmpty())
                : Objects.requireNonNull(toolPolicySupplier.get(), "toolPolicySupplier result");
        PermissionContext permissions = createPermissionContext(run, mode, permissionSnapshot);
        ProviderRouter.Route route =
            providerRouter == null
                ? new ProviderRouter.Route(null, client, protocol, false)
                : providerRouter.select(skills.preferredProvider().orElse(null));
        PromptAdditions additions = skillAdditions(promptAdditionsSupplier.get(), skills);
        Attempt attempt =
            openAttempt(
                run, mode, round, memoryOnlyRequest, policy, route, additions, requestId, 1);
        CollectedTurn turn = collectAttempt(collector, run, attempt, round * 2 - 1);
        recordUsage(turn, attempt);

        if (!turn.complete()
            && providerRouter != null
            && route.client() != providerRouter.main().client()
            && !run.cancellationToken().isCancelled()) {
          ProviderRouter.Route main = providerRouter.main();
          run.events()
              .publish(
                  new AgentEvent.ProviderFallback(
                      route.config().getName(), main.config().getName()));
          attempt =
              openAttempt(
                  run, mode, round, memoryOnlyRequest, policy, main, additions, requestId, 2);
          turn = collectAttempt(collector, run, attempt, round * 2);
          recordUsage(turn, attempt);
        }
        PromptRequest sentRequest = attempt.request();
        ContextRequest sentContextRequest = attempt.contextRequest();

        if (run.cancellationToken().isCancelled()) {
          finish(run, completedRounds, null, null);
          return;
        }
        if (!turn.complete()) {
          if (turn.errorKind() == StreamEvent.ErrorKind.CONTEXT_LENGTH
              && emergencyRecoveryRound != round
              && contextManager != null
              && sentContextRequest != null) {
            emergencyRecoveryRound = round;
            run.events().publish(new AgentEvent.CompactionStarted(ContextTrigger.EMERGENCY));
            var result =
                contextManager.forceCompact(
                    conversation, sentContextRequest, ContextTrigger.EMERGENCY);
            run.events().publish(new AgentEvent.CompactionComplete(result));
            dispatchCompact(mode, ContextTrigger.EMERGENCY, result, run);
            continue;
          }
          finish(
              run,
              completedRounds,
              turn.error() == null ? "LLM 流未完整结束。" : turn.error(),
              turn.errorKind() == StreamEvent.ErrorKind.CONTEXT_LENGTH
                  ? AgentEvent.ErrorCategory.CONTEXT
                  : AgentEvent.ErrorCategory.PROVIDER);
          return;
        }

        completedRounds = round;
        run.events().publish(new AgentEvent.TurnComplete(round));
        if (memoryOnlyRequest
            && turn.calls().stream()
                .map(call -> registry.get(call.toolName()).orElse(null))
                .anyMatch(tool -> tool == null || !tool.isSystem())) {
          // ponytail: 关键词启发式只负责工具隔离，复杂意图识别再引入独立解析器。
          finish(run, completedRounds, "记忆请求不允许调用工具。", AgentEvent.ErrorCategory.LOOP);
          return;
        }
        if (turn.calls().isEmpty()) {
          conversation.addAssistantMessage(turn.blocks());
          dispatchHook(
              HookEvent.STOP, Map.of("request_id", requestId, "iterations", completedRounds), run);
          notifyCompletedTurn(startingMessageCount, userText);
          finish(run, completedRounds, null, null);
          return;
        }

        Map<String, String> parseErrors = turn.parseErrors();
        List<ToolCall> executableCalls =
            turn.calls().stream()
                .filter(call -> !parseErrors.containsKey(call.toolUseId()))
                .toList();
        List<ToolCall> agentCalls =
            executableCalls.stream()
                .filter(call -> AgentTool.NAME.equals(call.toolName()))
                .filter(call -> registry.get(call.toolName()).filter(policy::isAllowed).isPresent())
                .toList();
        List<ToolCall> ordinaryCalls =
            executableCalls.stream().filter(call -> !agentCalls.contains(call)).toList();
        boolean loadsSkill =
            ordinaryCalls.stream().anyMatch(call -> LoadSkillTool.NAME.equals(call.toolName()));
        List<ToolInvocationResult> executed;
        if (loadsSkill) {
          executed = executeSkillLoads(ordinaryCalls, policy, mode, run, skills, requestId);
        } else {
          executed =
              permissionGate == null
                  ? executor.executeBatch(
                      ordinaryCalls, policy, run.cancellationToken(), requestId, mode)
                  : executor.executeBatch(ordinaryCalls, policy, permissions, requestId, mode);
        }
        if (!agentCalls.isEmpty()) {
          var withAgents = new ArrayList<ToolInvocationResult>(executed);
          withAgents.addAll(
              executeSubAgents(
                  agentCalls, sentRequest, turn.blocks(), policy, route, mode, run, requestId));
          executed = List.copyOf(withAgents);
        }
        for (Map.Entry<String, String> parseError : parseErrors.entrySet()) {
          turn.calls().stream()
              .filter(call -> call.toolUseId().equals(parseError.getKey()))
              .findFirst()
              .ifPresent(
                  call ->
                      executor.publishPostToolUse(
                          call,
                          new ToolInvocationResult(
                              call.toolUseId(),
                              ToolResult.error(parseError.getValue())
                                  .withMetadata(Map.of("status", "parse_error"))),
                          requestId,
                          mode,
                          run.cancellationToken()));
        }
        List<ToolResultBlock> resultBlocks =
            ToolResultAssembler.assemble(turn.calls(), executed, turn.parseErrors());
        // 工具调用和结果必须成对提交，取消发生在工具执行期间也不能留下悬空 assistant 消息。
        if (contextManager == null) {
          conversation.addToolTurn(turn.blocks(), resultBlocks);
        } else {
          contextManager.commitToolTurn(conversation, turn.blocks(), resultBlocks);
        }
        emitResults(run, turn.calls(), resultBlocks, executed);

        if (run.cancellationToken().isCancelled()) {
          finish(run, completedRounds, null, null);
          return;
        }

        boolean hasExecutableKnownTool =
            executableCalls.stream()
                .anyMatch(
                    call -> registry.get(call.toolName()).filter(policy::isAllowed).isPresent());
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
    } catch (ContextException error) {
      if (run.cancellationToken().isCancelled()) {
        finish(run, completedRounds, null, null);
      } else {
        finish(
            run,
            completedRounds,
            "上下文管理失败：" + safeMessage(error),
            AgentEvent.ErrorCategory.CONTEXT);
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
    } finally {
      skills.clear();
      run.removeCancellationHook(interruptThread);
    }
  }

  private Attempt openAttempt(
      AgentRun run,
      AgentMode mode,
      int round,
      boolean memoryOnlyRequest,
      ToolPolicy policy,
      ProviderRouter.Route route,
      PromptAdditions additions,
      String requestId,
      int attemptNumber) {
    List<String> deferredToolNames = memoryOnlyRequest ? List.of() : registry.deferredToolNames();
    List<Map<String, Object>> schemas =
        registry.toAPIFormateForModel(
            route.protocol(), tool -> memoryOnlyRequest ? tool.isSystem() : policy.isAllowed(tool));
    PromptAdditions baseAdditions = additions == null ? PromptAdditions.empty() : additions;
    PromptAdditions dynamic = baseAdditions;
    HookSessionState.ReminderBatch reminderBatch = null;
    if (hookEngine != null && hookState != null) {
      dispatchHook(
          HookEvent.MESSAGE_START,
          messageStartPayload(mode, requestId, round, attemptNumber, route),
          run);
      reminderBatch = hookState.snapshotPrompts();
      dynamic = withHookReminders(baseAdditions, reminderBatch.texts());
    }
    if (promptRequestFactory == null) {
      String systemPrompt = systemPromptProvider.apply(mode);
      try {
        CancellableLlmStream stream =
            systemPrompt == null
                ? route.client().openStream(conversation, schemas)
                : route.client().openStream(conversation, schemas, systemPrompt);
        return new Attempt(stream, null, null, requestId, round, attemptNumber, route, mode);
      } catch (RuntimeException error) {
        dispatchMessageEndFailure(mode, requestId, round, attemptNumber, run, error);
        throw error;
      } finally {
        if (reminderBatch != null) hookState.consumePrompts(reminderBatch);
      }
    }
    ContextRequest contextRequest =
        promptRequestFactory.createContextRequest(
            mode, round, round == 1, schemas, deferredToolNames, dynamic);
    if (contextManager != null) {
      ContextPreparation preparation =
          contextManager.prepareForRequest(
              conversation,
              contextRequest,
              trigger -> run.events().publish(new AgentEvent.CompactionStarted(trigger)));
      if (preparation.compacted()) {
        com.mewcode.compact.CompactResult compactResult = preparation.compactResult().orElseThrow();
        run.events().publish(new AgentEvent.CompactionComplete(compactResult));
        dispatchCompact(mode, ContextTrigger.AUTO, compactResult, run);
        if (hookState != null) {
          reminderBatch = hookState.snapshotPrompts();
          dynamic = withHookReminders(baseAdditions, reminderBatch.texts());
          contextRequest =
              promptRequestFactory.createContextRequest(
                  mode, round, round == 1, schemas, deferredToolNames, dynamic);
        }
      }
    }
    PromptRequest request =
        promptRequestFactory.create(
            mode,
            round,
            round == 1,
            conversation.getMessages(),
            schemas,
            deferredToolNames,
            dynamic);
    ContextRequest sent =
        new ContextRequest(request.systemSegments(), request.tools(), request.reminder());
    try {
      return new Attempt(
          route.client().openStream(request),
          request,
          sent,
          requestId,
          round,
          attemptNumber,
          route,
          mode);
    } catch (RuntimeException error) {
      dispatchMessageEndFailure(mode, requestId, round, attemptNumber, run, error);
      throw error;
    } finally {
      if (reminderBatch != null) hookState.consumePrompts(reminderBatch);
    }
  }

  private void recordUsage(CollectedTurn turn, Attempt attempt) {
    if (contextManager != null
        && attempt.request() != null
        && attempt.contextRequest() != null
        && turn.usage().isPresent()) {
      contextManager.recordUsage(
          turn.usage().get(), attempt.request().history(), attempt.contextRequest());
    }
  }

  private PromptAdditions skillAdditions(PromptAdditions base, SkillRun skills) {
    PromptAdditions value = base == null ? PromptAdditions.empty() : base;
    String summary = skillCatalog == null ? value.skillCatalog() : skillCatalog.promptSummary();
    String active = skills.promptBlock().isBlank() ? value.activeSkills() : skills.promptBlock();
    return new PromptAdditions(
        value.memoryIndex(),
        value.resumeReminder(),
        summary,
        active,
        value.hookReminders(),
        value.agentCatalog());
  }

  private PromptAdditions withHookReminders(PromptAdditions base, List<String> reminders) {
    PromptAdditions value = base == null ? PromptAdditions.empty() : base;
    var combined = new java.util.ArrayList<>(value.hookReminders());
    if (reminders != null) combined.addAll(reminders);
    return new PromptAdditions(
        value.memoryIndex(),
        value.resumeReminder(),
        value.skillCatalog(),
        value.activeSkills(),
        combined,
        value.agentCatalog());
  }

  private Map<String, Object> messageStartPayload(
      AgentMode mode, String requestId, int round, int attemptNumber, ProviderRouter.Route route) {
    var payload = baseHookPayload(mode);
    payload.put("request_id", requestId);
    payload.put("iteration", round);
    payload.put("attempt", attemptNumber);
    if (route.config() != null) {
      payload.put("provider", route.config().getName());
      payload.put("model", route.config().getModel());
    }
    payload.put("prompt", latestUserPrompt());
    return payload;
  }

  private void dispatchMessageEnd(Attempt attempt, CollectedTurn turn, AgentRun run) {
    var payload = baseHookPayload(attempt.mode());
    payload.put("request_id", attempt.requestId());
    payload.put("iteration", attempt.iteration());
    payload.put("attempt", attempt.attemptNumber());
    payload.put(
        "status",
        turn.complete()
            ? "success"
            : run.cancellationToken().isCancelled() ? "cancelled" : "error");
    payload.put("response", turn.text());
    payload.put(
        "tool_calls",
        turn.calls().stream()
            .map(
                call ->
                    Map.of(
                        "tool_use_id", call.toolUseId(),
                        "tool_name", call.toolName(),
                        "tool_input", call.arguments()))
            .toList());
    if (turn.error() != null && !turn.error().isBlank()) payload.put("error", turn.error());
    dispatchHook(HookEvent.MESSAGE_END, payload, run);
  }

  private void dispatchMessageEndFailure(
      AgentMode mode,
      String requestId,
      int iteration,
      int attemptNumber,
      AgentRun run,
      Throwable error) {
    var payload = baseHookPayload(mode);
    payload.put("request_id", requestId);
    payload.put("iteration", iteration);
    payload.put("attempt", attemptNumber);
    payload.put("status", run.cancellationToken().isCancelled() ? "cancelled" : "error");
    payload.put("response", "");
    payload.put("error", safeMessage(error));
    dispatchHook(HookEvent.MESSAGE_END, payload, run, mode);
  }

  private CollectedTurn collectAttempt(
      TurnStreamCollector collector, AgentRun run, Attempt attempt, int usageRound)
      throws InterruptedException {
    try {
      CollectedTurn turn = collector.collect(run, attempt.stream(), usageRound);
      dispatchMessageEnd(attempt, turn, run);
      return turn;
    } catch (InterruptedException error) {
      dispatchMessageEndFailure(
          attempt.mode(),
          attempt.requestId(),
          attempt.iteration(),
          attempt.attemptNumber(),
          run,
          error);
      throw error;
    } catch (RuntimeException error) {
      dispatchMessageEndFailure(
          attempt.mode(),
          attempt.requestId(),
          attempt.iteration(),
          attempt.attemptNumber(),
          run,
          error);
      throw error;
    }
  }

  private void dispatchCompact(
      AgentMode mode,
      ContextTrigger trigger,
      com.mewcode.compact.CompactResult result,
      AgentRun run) {
    dispatchHook(
        HookEvent.COMPACT,
        Map.of(
            "trigger", trigger.name().toLowerCase(Locale.ROOT),
            "before_tokens", result.beforeTokens(),
            "after_tokens", result.afterTokens()),
        run,
        mode);
  }

  private void dispatchHook(HookEvent event, Map<String, Object> fields, AgentRun run) {
    dispatchHook(event, fields, run, AgentMode.EXECUTE);
  }

  private void dispatchHook(
      HookEvent event, Map<String, Object> fields, AgentRun run, AgentMode mode) {
    if (hookEngine == null || hookState == null) return;
    var payload = baseHookPayload(mode);
    if (fields != null) payload.putAll(fields);
    hookEngine.dispatch(new HookInvocation(event, payload, hookState, run.cancellationToken()));
  }

  private java.util.LinkedHashMap<String, Object> baseHookPayload(AgentMode mode) {
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("cwd", executor.projectRoot().toString());
    if (hookSessionId != null) payload.put("session_id", hookSessionId);
    payload.put("mode", mode.name());
    return payload;
  }

  private String latestUserPrompt() {
    var messages = conversation.getMessages();
    for (int index = messages.size() - 1; index >= 0; index--) {
      if ("user".equals(messages.get(index).role())) return messages.get(index).textContent();
    }
    return "";
  }

  private List<ToolInvocationResult> executeSkillLoads(
      List<ToolCall> calls,
      ToolPolicy policy,
      AgentMode mode,
      AgentRun parentRun,
      SkillRun skills,
      String requestId) {
    var results = new java.util.ArrayList<ToolInvocationResult>();
    for (ToolCall call : calls) {
      HookRejection rejection =
          executor.prepareHook(call, requestId, mode, parentRun.cancellationToken()).orElse(null);
      ToolResult result;
      if (rejection != null) {
        result =
            ToolResult.error("Hook [" + rejection.hookName() + "] 拒绝工具调用：" + rejection.reason())
                .withMetadata(
                    Map.<String, Object>of(
                        "status", "hook_rejected",
                        "hook_name", rejection.hookName(),
                        "hook_reason", rejection.reason()));
      } else if (!registry.get(call.toolName()).filter(policy::isAllowed).isPresent()) {
        result = ToolResult.error("当前策略不允许调用工具：" + call.toolName() + "。");
      } else if (!LoadSkillTool.NAME.equals(call.toolName())) {
        result = ToolResult.error("Skill 已更新，请在下一轮根据新的可用工具重新选择工具。");
      } else {
        result = loadSkill(call, mode, parentRun, skills);
      }
      ToolInvocationResult invocationResult =
          new ToolInvocationResult(
              call.toolUseId(),
              result.withMetadata(
                  Map.<String, Object>of(
                      "status",
                      result
                          .metadata()
                          .getOrDefault("status", result.isError() ? "error" : "success"))));
      executor.publishPostToolUse(
          call, invocationResult, requestId, mode, parentRun.cancellationToken());
      results.add(invocationResult);
    }
    return List.copyOf(results);
  }

  private List<ToolInvocationResult> executeSubAgents(
      List<ToolCall> calls,
      PromptRequest sentRequest,
      List<com.mewcode.conversation.ContentBlock> assistantBlocks,
      ToolPolicy policy,
      ProviderRouter.Route route,
      AgentMode mode,
      AgentRun parentRun,
      String requestId) {
    var results = new ArrayList<ToolInvocationResult>(calls.size());
    for (ToolCall call : calls) {
      HookRejection rejection =
          executor.prepareHook(call, requestId, mode, parentRun.cancellationToken()).orElse(null);
      ToolResult result;
      if (rejection != null) {
        result =
            ToolResult.error("Hook [" + rejection.hookName() + "] 拒绝工具调用：" + rejection.reason())
                .withMetadata(
                    Map.<String, Object>of(
                        "status", "hook_rejected",
                        "hook_name", rejection.hookName(),
                        "hook_reason", rejection.reason()));
      } else if (subAgentRuntime == null) {
        result = ToolResult.error("SubAgent 运行器未初始化。").withMetadata(Map.of("status", "error"));
      } else if (sentRequest == null || route == null) {
        result =
            ToolResult.error("SubAgent 缺少父 Agent 的请求上下文。").withMetadata(Map.of("status", "error"));
      } else {
        try {
          result =
              subAgentRuntime.execute(
                  SubAgentRuntime.SubAgentInvocation.from(call.toolUseId(), call.arguments()),
                  new SubAgentRuntime.ParentAgentSnapshot(
                      sentRequest,
                      assistantBlocks,
                      policy,
                      route,
                      mode,
                      parentRun,
                      hookSessionId == null ? requestId : hookSessionId));
        } catch (RuntimeException error) {
          result =
              ToolResult.error("SubAgent 执行失败：" + safeMessage(error))
                  .withMetadata(Map.of("status", "error"));
        }
      }
      ToolInvocationResult invocationResult =
          new ToolInvocationResult(
              call.toolUseId(),
              result.withMetadata(
                  Map.<String, Object>of(
                      "status",
                      result
                          .metadata()
                          .getOrDefault("status", result.isError() ? "error" : "success"))));
      executor.publishPostToolUse(
          call, invocationResult, requestId, mode, parentRun.cancellationToken());
      results.add(invocationResult);
    }
    return List.copyOf(results);
  }

  private ToolResult loadSkill(ToolCall call, AgentMode mode, AgentRun parentRun, SkillRun skills) {
    Object rawName = call.arguments().get("name");
    Object rawArguments = call.arguments().getOrDefault("arguments", "");
    if (!(rawName instanceof String name) || name.isBlank()) {
      return ToolResult.error("请传入要加载的 Skill 名称。");
    }
    if (!(rawArguments instanceof String arguments)) {
      return ToolResult.error("Skill arguments 必须是字符串。");
    }
    if (skillRefresher != null) skillRefresher.get();
    SkillDefinition definition = skillCatalog == null ? null : skillCatalog.find(name).orElse(null);
    if (definition == null) return ToolResult.error("未知 Skill：" + name);
    if (definition.meta().mode() == SkillDefinition.Mode.FORK) {
      if (forkRunner == null) return ToolResult.error("fork Skill 运行器未初始化。");
      return forkRunner.apply(
          new SkillExecutor.ForkRequest(
              definition, arguments, conversation.getMessages(), mode, parentRun));
    }
    skills.activate(definition, arguments);
    ToolPolicy next =
        ToolPolicy.forModeAndTools(mode, skills.allowedTools(), !skills.activeSkills().isEmpty());
    List<String> filtered =
        definition.meta().tools().stream()
            .filter(nameOfTool -> registry.get(nameOfTool).filter(next::isAllowed).isEmpty())
            .toList();
    String suffix = filtered.isEmpty() ? "" : "；当前模式已过滤工具：" + String.join(", ", filtered);
    return ToolResult.success("已加载 Skill " + definition.meta().name() + suffix);
  }

  private record Attempt(
      CancellableLlmStream stream,
      PromptRequest request,
      ContextRequest contextRequest,
      String requestId,
      int iteration,
      int attemptNumber,
      ProviderRouter.Route route,
      AgentMode mode) {}

  private static boolean isMemoryOnlyRequest(String userText) {
    String text = userText == null ? "" : userText.toLowerCase(Locale.ROOT);
    boolean memoryIntent =
        text.contains("记住")
            || text.contains("记下")
            || text.contains("记着")
            || text.contains("保存到记忆")
            || text.contains("保存到项目记忆")
            || text.contains("保存到个人记忆")
            || text.contains("保存为项目知识")
            || text.contains("保存为个人偏好")
            || text.contains("存入记忆")
            || text.contains("加入记忆")
            || text.contains("加入项目记忆")
            || text.contains("加入个人记忆")
            || text.contains("记录到项目知识")
            || text.contains("记录到项目记忆")
            || text.contains("记录到个人记忆")
            || text.contains("记录到长期记忆")
            || text.contains("作为项目知识")
            || text.contains("作为个人偏好")
            || text.contains("记为项目知识")
            || text.contains("记为个人偏好")
            || text.contains("写入项目知识")
            || text.contains("写入项目记忆")
            || text.contains("写入个人记忆")
            || text.contains("remember")
            || text.contains("save this in memory")
            || text.contains("store this in memory");
    boolean memoryContext =
        text.contains("项目知识")
            || text.contains("项目记忆")
            || text.contains("个人记忆")
            || text.contains("个人偏好")
            || text.contains("长期记忆")
            || text.contains("长期 memory");
    memoryIntent |=
        memoryContext
            && (text.contains("记录")
                || text.contains("保存")
                || text.contains("写入")
                || text.contains("加入")
                || text.contains("作为"));
    if (!memoryIntent) return false;
    boolean fileReference =
        text.contains(".mewcode/")
            || text.matches("(?s).*\\.[a-z0-9]{1,12}(?:$|[\\s,，。:：、;；!?！？]).*");
    boolean fileMutation =
        text.contains("文件")
            && (text.contains("修改")
                || text.contains("编辑")
                || text.contains("写入")
                || text.contains("更新")
                || text.contains("创建")
                || text.contains("删除")
                || text.contains("记录")
                || text.contains("保存")
                || text.contains("添加")
                || text.contains("追加")
                || text.contains("覆盖"));
    return !fileReference && !fileMutation;
  }

  private void notifyCompletedTurn(int startingMessageCount, String userText) {
    try {
      List<Message> history = conversation.getMessages();
      int start = -1;
      Message currentUser = new Message("user", userText);
      for (int index = history.size() - 1; index >= 0; index--) {
        if (currentUser.equals(history.get(index))) {
          start = index;
          break;
        }
      }
      if (start < 0) {
        start =
            startingMessageCount >= history.size()
                ? 0
                : Math.min(Math.max(startingMessageCount, 0), history.size());
      }
      completionListener.accept(List.copyOf(history.subList(start, history.size())));
    } catch (RuntimeException ignored) {
      // 后台 memory/title 更新失败不应让已完成的 Agent 响应变成失败。
    }
  }

  private PermissionContext createPermissionContext(
      AgentRun run, AgentMode mode, PermissionRuntime.Snapshot snapshot) {
    if (permissionGate == null) return null;
    PermissionMode effectiveMode =
        mode == AgentMode.PLAN
            ? PermissionMode.PLAN
            : snapshot == null ? configuredPermissionMode : snapshot.mode();
    return new PermissionContext(
        executor.projectRoot(),
        effectiveMode,
        snapshot == null ? permissionRuleEngine : snapshot.ruleEngine(),
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
  private void finish(
      AgentRun run, int totalRounds, String errorMessage, AgentEvent.ErrorCategory category) {
    if (errorMessage != null && category != null) {
      dispatchHook(
          HookEvent.ERROR,
          Map.of("error", errorMessage, "category", category.name().toLowerCase(Locale.ROOT)),
          run);
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
