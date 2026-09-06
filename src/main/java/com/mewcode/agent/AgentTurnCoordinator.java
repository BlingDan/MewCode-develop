package com.mewcode.agent;

import com.mewcode.compact.ContextException;
import com.mewcode.compact.ContextManager;
import com.mewcode.compact.ContextPreparation;
import com.mewcode.compact.ContextRequest;
import com.mewcode.compact.ContextTrigger;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.PromptRequest;
import com.mewcode.llm.StreamEvent;
import com.mewcode.permission.BashSandbox;
import com.mewcode.permission.PathAuthorizationStore;
import com.mewcode.permission.PermissionContext;
import com.mewcode.permission.PermissionMode;
import com.mewcode.permission.PermissionRuntime;
import com.mewcode.skill.ProviderRouter;
import com.mewcode.skill.SkillCatalog;
import com.mewcode.skill.SkillDefinition;
import com.mewcode.skill.SkillExecutor;
import com.mewcode.skill.SkillRun;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolCall;
import com.mewcode.tool.ToolExecutor;
import com.mewcode.tool.ToolInvocationResult;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import com.mewcode.tool.impl.LoadSkillTool;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

  private final LlmClient client;
  private final ToolRegistry registry;
  private final ToolExecutor executor;
  private final ConversationManager conversation;
  private final ToolApiProtocol protocol;
  private final AgentLoopConfig config;
  private final PromptRequestFactory promptRequestFactory;
  private final ContextManager contextManager;
  private final PermissionRuntime permissionRuntime;
  private final PathAuthorizationStore pathAuthorizationStore;
  private final BashSandbox bashSandbox;
  private volatile Supplier<PromptAdditions> promptAdditionsSupplier = PromptAdditions::empty;
  private volatile Consumer<List<Message>> completionListener = ignored -> {};
  private volatile SkillCatalog skillCatalog;
  private volatile Supplier<SkillCatalog.RefreshResult> skillRefresher;
  private volatile ProviderRouter providerRouter;
  private volatile Function<SkillExecutor.ForkRequest, ToolResult> forkRunner;

  /** 创建使用结构化提示、上下文管理和权限快照的协调器。 */
  public AgentTurnCoordinator(
      LlmClient client,
      ToolRegistry registry,
      ToolExecutor executor,
      ConversationManager conversation,
      ToolApiProtocol protocol,
      AgentLoopConfig config,
      PromptRequestFactory promptRequestFactory,
      ContextManager contextManager,
      PermissionRuntime permissionRuntime,
      PathAuthorizationStore pathAuthorizationStore,
      BashSandbox bashSandbox) {
    this.client = Objects.requireNonNull(client, "client");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.conversation = Objects.requireNonNull(conversation, "conversation");
    this.protocol = Objects.requireNonNull(protocol, "protocol");
    this.config = Objects.requireNonNull(config, "config").copy();
    this.config.validate();
    this.promptRequestFactory =
        Objects.requireNonNull(promptRequestFactory, "promptRequestFactory");
    this.contextManager = Objects.requireNonNull(contextManager, "contextManager");
    this.permissionRuntime = Objects.requireNonNull(permissionRuntime, "permissionRuntime");
    this.pathAuthorizationStore =
        Objects.requireNonNull(pathAuthorizationStore, "pathAuthorizationStore");
    this.bashSandbox = Objects.requireNonNull(bashSandbox, "bashSandbox");
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
    AgentMode effectiveMode = Objects.requireNonNull(mode, "mode");
    var run = new AgentRun();
    run.setPermissionPublisher(
        request -> run.events().publish(new AgentEvent.PermissionRequested(request)));
    try {
      int startingMessageCount = conversation.getMessages().size();
      PermissionRuntime.Snapshot permissionSnapshot = permissionRuntime.snapshot();
      conversation.addUserMessage(userText);
      Thread.startVirtualThread(
          () ->
              runLoop(
                  run,
                  effectiveMode,
                  startingMessageCount,
                  userText,
                  permissionSnapshot,
                  runSkills));
    } catch (Throwable error) {
      finish(run, 0, "Agent Loop 启动失败：" + safeMessage(error), AgentEvent.ErrorCategory.LOOP);
    }
    return run;
  }

  /** 启动带可选保留重点的手动压缩。 */
  public AgentRun startManualCompaction(AgentMode mode, String focus) {
    AgentMode effectiveMode = Objects.requireNonNull(mode, "mode");
    var run = new AgentRun();
    run.setPermissionPublisher(
        request -> run.events().publish(new AgentEvent.PermissionRequested(request)));
    try {
      Thread.startVirtualThread(() -> manualCompactionLoop(run, effectiveMode, focus));
    } catch (Throwable error) {
      finish(run, 0, "手动上下文压缩启动失败：" + safeMessage(error), AgentEvent.ErrorCategory.CONTEXT);
    }
    return run;
  }

  /** 返回手动压缩请求的当前 Token 估算，不触发 Provider。 */
  public long estimateManualCompactionTokens(AgentMode mode) {
    ContextRequest request =
        promptRequestFactory.createContextRequest(
            Objects.requireNonNull(mode, "mode"), 1, true, List.of(), List.of());
    return contextManager.estimateTokens(conversation, request);
  }

  private void manualCompactionLoop(AgentRun run, AgentMode mode, String focus) {
    Thread currentThread = Thread.currentThread();
    Runnable interruptThread = currentThread::interrupt;
    run.addCancellationHook(interruptThread);
    try {
      ContextRequest request =
          promptRequestFactory.createContextRequest(mode, 1, true, List.of(), List.of());
      run.events().publish(new AgentEvent.CompactionStarted(ContextTrigger.MANUAL));
      var result = contextManager.forceCompact(conversation, request, ContextTrigger.MANUAL, focus);
      run.events().publish(new AgentEvent.CompactionComplete(result));
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

  /** 设置每轮请求前读取的 memory/恢复提醒快照。 */
  public void setPromptAdditionsSupplier(Supplier<PromptAdditions> supplier) {
    promptAdditionsSupplier = Objects.requireNonNull(supplier, "supplier");
  }

  /** 设置自然完成一轮后的异步通知；通知失败不会改变 Agent Loop 结果。 */
  public void setCompletionListener(Consumer<List<Message>> listener) {
    completionListener = Objects.requireNonNull(listener, "listener");
  }

  /** 注入当前进程的 Skill 目录、刷新器、Provider 路由和 fork 执行器。 */
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
      SkillRun skills) {
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
      while (!run.cancellationToken().isCancelled()
          && completedRounds < config.getMaxIterations()) {
        int round = completedRounds + 1;
        var policy =
            ToolPolicy.forModeAndTools(
                mode, skills.allowedTools(), !skills.activeSkills().isEmpty());
        PermissionContext permissions = createPermissionContext(run, mode, permissionSnapshot);
        ProviderRouter.Route route =
            providerRouter == null
                ? new ProviderRouter.Route(null, client, protocol, false)
                : providerRouter.select(skills.preferredProvider().orElse(null));
        PromptAdditions additions = skillAdditions(promptAdditionsSupplier.get(), skills);
        Attempt attempt =
            openAttempt(run, mode, round, memoryOnlyRequest, policy, route, additions);
        CollectedTurn turn = collector.collect(run, attempt.stream(), round * 2 - 1);
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
          attempt = openAttempt(run, mode, round, memoryOnlyRequest, policy, main, additions);
          turn = collector.collect(run, attempt.stream(), round * 2);
          recordUsage(turn, attempt);
        }
        ContextRequest sentContextRequest = attempt.contextRequest();

        if (run.cancellationToken().isCancelled()) {
          finish(run, completedRounds, null, null);
          return;
        }
        if (!turn.complete()) {
          if (turn.errorKind() == StreamEvent.ErrorKind.CONTEXT_LENGTH
              && emergencyRecoveryRound != round) {
            emergencyRecoveryRound = round;
            run.events().publish(new AgentEvent.CompactionStarted(ContextTrigger.EMERGENCY));
            var result =
                contextManager.forceCompact(
                    conversation, sentContextRequest, ContextTrigger.EMERGENCY);
            run.events().publish(new AgentEvent.CompactionComplete(result));
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
          notifyCompletedTurn(startingMessageCount, userText);
          finish(run, completedRounds, null, null);
          return;
        }

        Map<String, String> parseErrors = turn.parseErrors();
        List<ToolCall> executableCalls =
            turn.calls().stream()
                .filter(call -> !parseErrors.containsKey(call.toolUseId()))
                .toList();
        boolean loadsSkill =
            executableCalls.stream().anyMatch(call -> LoadSkillTool.NAME.equals(call.toolName()));
        List<ToolInvocationResult> executed;
        if (loadsSkill) {
          executed = executeSkillLoads(executableCalls, mode, run, skills);
        } else {
          executed = executor.executeBatch(executableCalls, policy, permissions);
        }
        List<ToolResultBlock> resultBlocks =
            ToolResultAssembler.assemble(turn.calls(), executed, turn.parseErrors());
        // 工具调用和结果必须成对提交，取消发生在工具执行期间也不能留下悬空 assistant 消息。
        contextManager.commitToolTurn(conversation, turn.blocks(), resultBlocks);
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
      PromptAdditions additions) {
    List<String> deferredToolNames = memoryOnlyRequest ? List.of() : registry.deferredToolNames();
    List<Map<String, Object>> schemas =
        registry.toApiFormatForModel(
            route.protocol(), tool -> memoryOnlyRequest ? tool.isSystem() : policy.isAllowed(tool));
    ContextRequest contextRequest =
        promptRequestFactory.createContextRequest(
            mode, round, round == 1, schemas, deferredToolNames, additions);
    ContextPreparation preparation =
        contextManager.prepareForRequest(
            conversation,
            contextRequest,
            trigger -> run.events().publish(new AgentEvent.CompactionStarted(trigger)));
    if (preparation.compacted()) {
      run.events()
          .publish(new AgentEvent.CompactionComplete(preparation.compactResult().orElseThrow()));
    }
    PromptRequest request =
        promptRequestFactory.create(
            mode,
            round,
            round == 1,
            conversation.getMessages(),
            schemas,
            deferredToolNames,
            additions);
    ContextRequest sent =
        new ContextRequest(request.systemSegments(), request.tools(), request.reminder());
    return new Attempt(route.client().openStream(request), request, sent);
  }

  private void recordUsage(CollectedTurn turn, Attempt attempt) {
    if (turn.usage().isPresent()) {
      contextManager.recordUsage(
          turn.usage().get(), attempt.request().history(), attempt.contextRequest());
    }
  }

  private PromptAdditions skillAdditions(PromptAdditions base, SkillRun skills) {
    PromptAdditions value = base == null ? PromptAdditions.empty() : base;
    String summary = skillCatalog == null ? value.skillCatalog() : skillCatalog.promptSummary();
    String active = skills.promptBlock().isBlank() ? value.activeSkills() : skills.promptBlock();
    return new PromptAdditions(value.memoryIndex(), value.resumeReminder(), summary, active);
  }

  private List<ToolInvocationResult> executeSkillLoads(
      List<ToolCall> calls, AgentMode mode, AgentRun parentRun, SkillRun skills) {
    var results = new java.util.ArrayList<ToolInvocationResult>();
    for (ToolCall call : calls) {
      if (!LoadSkillTool.NAME.equals(call.toolName())) {
        results.add(
            new ToolInvocationResult(
                call.toolUseId(), ToolResult.error("Skill 已更新，请在下一轮根据新的可用工具重新选择。")));
        continue;
      }
      results.add(
          new ToolInvocationResult(call.toolUseId(), loadSkill(call, mode, parentRun, skills)));
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
      CancellableLlmStream stream, PromptRequest request, ContextRequest contextRequest) {}

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
    PermissionMode effectiveMode = mode == AgentMode.PLAN ? PermissionMode.PLAN : snapshot.mode();
    return new PermissionContext(
        executor.projectRoot(),
        effectiveMode,
        snapshot.ruleEngine(),
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

  private static String safeMessage(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
  }
}
