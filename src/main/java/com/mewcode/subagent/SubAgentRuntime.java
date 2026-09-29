package com.mewcode.subagent;

import com.mewcode.agent.AgentEvent;
import com.mewcode.agent.AgentLoopConfig;
import com.mewcode.agent.AgentMode;
import com.mewcode.agent.AgentRun;
import com.mewcode.agent.AgentTurnCoordinator;
import com.mewcode.agent.PromptAdditions;
import com.mewcode.agent.PromptRequestFactory;
import com.mewcode.agent.ToolPolicy;
import com.mewcode.compact.ContextManager;
import com.mewcode.conversation.ContentBlock;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.hook.HookEngine;
import com.mewcode.hook.HookSessionState;
import com.mewcode.llm.PromptRequest;
import com.mewcode.permission.BashSandbox;
import com.mewcode.permission.PathAuthorizationStore;
import com.mewcode.permission.PermissionGate;
import com.mewcode.permission.PermissionMode;
import com.mewcode.permission.PermissionRuleEngine;
import com.mewcode.skill.ProviderRouter;
import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.ToolExecutor;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import com.mewcode.util.Closeables;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** 定义式和 Fork 式 SubAgent 的统一运行入口。 */
public final class SubAgentRuntime {

  private static final String FORK_RULES = "这是 Fork 工作进程。不要提问、不要请求确认、不要创建子 Agent，只完成分配范围并返回简洁结果。";

  private final AgentCatalog catalog;
  private volatile com.mewcode.worktree.AgentWorkspace workspace;
  private final SubAgentTaskManager taskManager;
  private final ToolRegistry registry;
  private final Path projectRoot;
  private final PromptRequestFactory promptRequestFactory;
  private final AgentLoopConfig parentLoopConfig;
  private final PermissionGate permissionGate;
  private final PermissionRuleEngine permissionRuleEngine;
  private final PathAuthorizationStore pathAuthorizationStore;
  private final BashSandbox bashSandbox;
  private final HookEngine hookEngine;
  private final String hookSessionId;
  private final long autoBackgroundMs;
  private final ProviderRouter providerRouter;

  public SubAgentRuntime(
      AgentCatalog catalog,
      SubAgentTaskManager taskManager,
      ToolRegistry registry,
      Path projectRoot,
      PromptRequestFactory promptRequestFactory,
      AgentLoopConfig parentLoopConfig,
      PermissionGate permissionGate,
      PermissionRuleEngine permissionRuleEngine,
      PathAuthorizationStore pathAuthorizationStore,
      BashSandbox bashSandbox,
      HookEngine hookEngine,
      String hookSessionId,
      long autoBackgroundMs,
      ProviderRouter providerRouter) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.projectRoot =
        Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
    this.promptRequestFactory =
        Objects.requireNonNull(promptRequestFactory, "promptRequestFactory");
    this.parentLoopConfig = Objects.requireNonNull(parentLoopConfig, "parentLoopConfig").copy();
    this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
    this.permissionRuleEngine =
        Objects.requireNonNull(permissionRuleEngine, "permissionRuleEngine");
    this.pathAuthorizationStore =
        Objects.requireNonNull(pathAuthorizationStore, "pathAuthorizationStore");
    this.bashSandbox = Objects.requireNonNull(bashSandbox, "bashSandbox");
    this.hookEngine = hookEngine;
    this.hookSessionId = hookSessionId;
    this.providerRouter = providerRouter;
    if (autoBackgroundMs <= 0)
      throw new IllegalArgumentException("autoBackgroundMs must be positive");
    this.autoBackgroundMs = autoBackgroundMs;
  }

  public void configureWorkspace(com.mewcode.worktree.AgentWorkspace workspace) {
    this.workspace = Objects.requireNonNull(workspace);
  }

  public ToolResult execute(SubAgentInvocation invocation, ParentAgentSnapshot parent) {
    Objects.requireNonNull(invocation, "invocation");
    Objects.requireNonNull(parent, "parent");
    if (invocation.subagentType().isBlank()) {
      return executeTask(invocation, parent, null, true, parent.route());
    }
    SubAgentSpec spec =
        (workspace == null ? catalog : workspace.agentCatalog(parentLoopConfig.getMaxIterations()))
            .find(invocation.subagentType())
            .orElse(null);
    if (spec == null) return ToolResult.error("未知 SubAgent：" + invocation.subagentType());
    String model = invocation.model() == null ? spec.model() : invocation.model();
    ProviderRouter.Route route = resolveRoute(parent.route(), model);
    if (route == null) return ToolResult.error("请求的 SubAgent 模型不可用：" + model);
    return executeTask(invocation, parent, spec, false, route);
  }

  private ToolResult executeTask(
      SubAgentInvocation invocation,
      ParentAgentSnapshot parent,
      SubAgentSpec spec,
      boolean fork,
      ProviderRouter.Route childRoute) {
    String trustedTaskId = taskManager.allocateTaskId();
    boolean isolated = spec != null && spec.isolation() == SubAgentSpec.IsolationMode.WORKTREE;
    var dispatchContext =
        parent.dispatchContext() == null
            ? (workspace == null
                ? new com.mewcode.tool.ToolExecutionContext(projectRoot, new FileStateCache())
                : workspace.capture(parent.parentRun().cancellationToken()))
            : parent.dispatchContext();
    Path dispatchCwd = dispatchContext.projectRoot();
    com.mewcode.worktree.AgentWorktree adapter;
    AutoCloseable parentUse;
    String frozenHead;
    try {
      parentUse =
          dispatchContext.workspaceScope() == null
              ? () -> {}
              : dispatchContext.workspaceScope().retain();
      if (isolated) {
        if (workspace == null) throw new IllegalStateException("隔离运行缺少工作区管理器");
        adapter =
            new com.mewcode.worktree.AgentWorktree(
                workspace.manager(),
                trustedTaskId,
                Path.of(System.getProperty("user.home")),
                parent.sessionId());
        try {
          frozenHead =
              workspace.manager().freezeHead(dispatchCwd, parent.parentRun().cancellationToken());
        } catch (RuntimeException failure) {
          Closeables.closeQuietly(parentUse);
          throw failure;
        }
      } else {
        adapter = null;
        frozenHead = null;
      }
    } catch (RuntimeException failure) {
      return ToolResult.error("子任务隔离启动失败：" + safeMessage(failure));
    }
    var startupToken = new com.mewcode.agent.CancellationToken();
    var childWorktree = new AtomicReference<com.mewcode.worktree.AgentWorktree.Result>();
    var retainedNotice = new AtomicReference<String>("");
    boolean publishImmediately = fork || invocation.runInBackground();
    boolean denyPermissionsWhenBackground =
        !fork && spec.permissionMode() == SubAgentSpec.PermissionMode.DEFAULT;
    String sessionId = parent.sessionId();
    String prompt = invocation.prompt();
    ConversationManager childConversation =
        fork ? forkConversation(parent, invocation) : new ConversationManager();
    PromptRequestFactory childPromptFactory =
        fork ? forkPromptFactory(parent) : definitionPromptFactory(spec);
    AgentMode childMode = parent.mode();
    AgentLoopConfig childConfig =
        new AgentLoopConfig(
            spec == null ? parentLoopConfig.getMaxIterations() : spec.maxTurns(),
            parentLoopConfig.getUnknownToolRoundLimit());
    var taskId = new AtomicReference<String>(trustedTaskId);
    var cleanup = new AtomicReference<Runnable>();
    var removePermissionDelegate = new AtomicReference<Runnable>();
    var published = new CompletableFuture<String>();
    SubAgentTaskManager.TaskRequest request =
        new SubAgentTaskManager.TaskRequest(
            sessionId,
            fork ? SubAgentTaskManager.TaskType.FORK : SubAgentTaskManager.TaskType.DEFINITION,
            invocation.description(),
            prompt,
            publishImmediately,
            denyPermissionsWhenBackground,
            () -> {
              Path childRoot = dispatchCwd;
              try {
                if (adapter != null) {
                  var created =
                      adapter.create(
                          dispatchCwd, "temp-" + trustedTaskId, frozenHead, startupToken);
                  childWorktree.set(created);
                  childRoot = created.worktreePath();
                }
              } finally {
                if (adapter != null) Closeables.closeQuietly(parentUse);
              }
              HookSessionState childHookState = new HookSessionState();
              var childWorkspace =
                  adapter != null
                      ? adapter.workspace()
                      : workspace == null
                          ? null
                          : workspace.forkForChild(dispatchContext, trustedTaskId);
              var childRegistry = isolated ? new ToolRegistry() : registry;
              if (isolated) {
                registry.getAll().stream()
                    .filter(tool -> !tool.isSkillTool())
                    .forEach(childRegistry::register);
                childRegistry.register(new com.mewcode.tool.impl.ToolSearchTool(childRegistry));
              }
              var childSkills = childWorkspace == null ? null : childWorkspace.skillCatalog();
              if (isolated && childSkills != null) {
                var scripts = new ArrayList<com.mewcode.tool.Tool>();
                for (var skill : childSkills.list())
                  for (var tool : skill.tools())
                    scripts.add(new com.mewcode.skill.ScriptTool(tool, skill.directory()));
                childRegistry.replaceSkillTools(scripts);
              }
              HookEngine childHooks =
                  isolated
                      ? new HookEngine(
                          com.mewcode.config.HookConfigLoader.load(
                              childRoot, workspace.userHome(), ignored -> {}),
                          new com.mewcode.tool.support.CommandRunner(bashSandbox),
                          ignored -> {})
                      : hookEngine;
              ToolExecutor childExecutor =
                  new ToolExecutor(childRegistry, childRoot, new FileStateCache(), permissionGate);
              if (childWorkspace != null) childExecutor.configureWorkspace(childWorkspace);
              ContextManager childContext =
                  new ContextManager(
                      childRoot,
                      childRoute.client(),
                      childRoute.config() == null
                          ? 128_000
                          : childRoute.config().getContextWindowTokens());
              PromptRequestFactory effectivePrompt = childPromptFactory;
              if (isolated && childWorkspace != null) {
                var segments = new ArrayList<String>();
                segments.add(spec.systemPrompt());
                segments.addAll(childWorkspace.systemPrompt().systemSegments());
                segments.add(adapter.buildNotice(dispatchCwd, childRoot));
                effectivePrompt =
                    new PromptRequestFactory(childWorkspace::systemPrompt)
                        .withFixedSystemSegments(segments);
              }
              cleanup.set(
                  () -> {
                    Runnable removeDelegate = removePermissionDelegate.getAndSet(null);
                    if (removeDelegate != null) removeDelegate.run();
                    if (childHooks != null) {
                      childHooks.cancelSession(childHookState);
                      childHooks.awaitSessionIdle(childHookState, java.time.Duration.ofSeconds(2));
                    }
                    childExecutor.close();
                    Closeables.closeQuietly(parentUse);
                    childContext.close();
                    if (childWorkspace != null) {
                      childWorkspace.closeMemories();
                      if (adapter != null) adapter.release();
                    }
                    if (isolated && childHooks != null) childHooks.close();
                  });
              try {
                PermissionMode permissionMode =
                    fork
                            || spec == null
                            || spec.permissionMode() == SubAgentSpec.PermissionMode.DONT_ASK
                        ? PermissionMode.BYPASS_PERMISSIONS
                        : PermissionMode.DEFAULT;
                var child =
                    new AgentTurnCoordinator(
                        childRoute.client(),
                        childRegistry,
                        childExecutor,
                        childConversation,
                        childRoute.protocol(),
                        childConfig,
                        effectivePrompt,
                        childContext,
                        permissionGate,
                        new com.mewcode.permission.PermissionRuntime(
                            permissionMode, new PermissionRuleEngine(permissionRuleEngine.rules())),
                        pathAuthorizationStore,
                        bashSandbox);
                child.setPromptAdditionsSupplier(
                    () ->
                        childWorkspace == null
                            ? PromptAdditions.empty()
                            : new PromptAdditions(
                                childWorkspace.memory(true, ignored -> {}).indexText(),
                                java.util.Optional.empty(),
                                "",
                                "",
                                List.of(),
                                ""));
                child.setToolPolicySupplier(
                    () -> {
                      boolean background =
                          publishImmediately
                              || (taskId.get() != null
                                  && taskManager.isPublished(sessionId, taskId.get()));
                      return ToolPolicy.forSubAgent(
                          childRegistry, parent.toolPolicy(), specForPolicy(spec), background);
                    });
                if (isolated && childSkills != null)
                  child.configureSkills(
                      childSkills,
                      () ->
                          childSkills.refreshHot(
                              childRegistry.ordinaryToolNames(), java.util.Set.of("worktree")),
                      providerRouter,
                      ignored -> ToolResult.error("子任务不支持嵌套 Skill fork。"));
                if (childHooks != null) {
                  child.setHookSessionId(isolated ? trustedTaskId : hookSessionId);
                  child.configureHooks(childHooks, childHookState);
                }
                startupToken.throwIfCancelled();
                AgentRun run =
                    child.startRunWithCancellation(
                        prompt, childMode, new com.mewcode.skill.SkillRun(), startupToken);
                if (!fork
                    && spec != null
                    && spec.permissionMode() == SubAgentSpec.PermissionMode.DEFAULT) {
                  Runnable removeDelegate = parent.parentRun().delegatePermissionsTo(run);
                  removePermissionDelegate.set(removeDelegate);
                }
                Runnable resources = cleanup.get();
                cleanup.set(
                    () -> {
                      run.close();
                      resources.run();
                    });
                return run;
              } catch (RuntimeException error) {
                Runnable resources = cleanup.getAndSet(null);
                if (resources != null) resources.run();
                childHookState.close();
                throw error;
              }
            },
            () -> {
              Runnable removeDelegate = removePermissionDelegate.getAndSet(null);
              if (removeDelegate != null) removeDelegate.run();
              String id = taskId.get();
              if (id != null) {
                parent.parentRun().events().publish(new AgentEvent.SubAgentBackgrounded(id));
                published.complete(id);
              }
            },
            () -> {
              Closeables.closeQuietly(parentUse);
              Runnable action = cleanup.getAndSet(null);
              if (action != null) action.run();
              if (adapter != null) {
                adapter.release();
                var created = childWorktree.get();
                if (created != null) {
                  String detail;
                  try {
                    adapter.remove(created, new com.mewcode.agent.CancellationToken());
                    detail = "无成果工作树已清理";
                  } catch (RuntimeException failure) {
                    detail = "工作树已保留：" + safeMessage(failure);
                  }
                  retainedNotice.set(
                      detail
                          + "\n路径："
                          + created.worktreePath()
                          + "\n分支："
                          + created.worktreeBranch());
                }
              }
            },
            event -> {
              if (event instanceof AgentEvent.PermissionRequested) {
                parent.parentRun().events().publish(event);
              }
            },
            retainedNotice::get,
            startupToken);
    SubAgentTaskManager.TaskHandle handle;
    try {
      handle = taskManager.start(trustedTaskId, request);
      taskId.set(handle.taskId());
    } catch (RuntimeException error) {
      Closeables.closeQuietly(parentUse);
      return ToolResult.error("无法启动 SubAgent。");
    }

    Runnable cancelTask = () -> taskManager.cancel(sessionId, handle.taskId());
    parent.parentRun().addCancellationHook(cancelTask);
    handle
        .completion()
        .whenComplete((ignored, error) -> parent.parentRun().removeCancellationHook(cancelTask));

    if (!publishImmediately) {
      parent
          .parentRun()
          .setBackgroundRequester(() -> taskManager.publish(sessionId, handle.taskId()));
      Thread.startVirtualThread(
          () -> {
            try {
              Thread.sleep(autoBackgroundMs);
              if (!handle.completion().isDone()) taskManager.publish(sessionId, handle.taskId());
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
            }
          });
    }

    if (publishImmediately) return asyncResult(handle.taskId());
    try {
      Object completed = CompletableFuture.anyOf(handle.completion(), published).get();
      if (completed instanceof String taskIdValue) {
        parent.parentRun().clearBackgroundRequester();
        return asyncResult(taskIdValue);
      }
      SubAgentTaskManager.TaskSnapshot snapshot = (SubAgentTaskManager.TaskSnapshot) completed;
      parent.parentRun().clearBackgroundRequester();
      return snapshot.status() == SubAgentTaskManager.Status.COMPLETED
          ? ToolResult.success(snapshot.result())
          : ToolResult.error(snapshot.error().isBlank() ? "子 Agent 执行失败。" : snapshot.error());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      taskManager.update(
          sessionId,
          new SubAgentTaskManager.TaskUpdate(
              handle.taskId(), SubAgentTaskManager.Status.CANCELLED, null, null));
      return ToolResult.error("子 Agent 已取消。");
    } catch (ExecutionException error) {
      return ToolResult.error("子 Agent 执行失败：" + safeMessage(error.getCause()));
    }
  }

  private PromptRequestFactory definitionPromptFactory(SubAgentSpec spec) {
    List<String> base = promptRequestFactory.systemPrompt().systemSegments();
    String environment = base.isEmpty() ? "" : base.getLast();
    return promptRequestFactory.withFixedSystemSegments(
        environment.isBlank()
            ? List.of(spec.systemPrompt())
            : List.of(spec.systemPrompt(), environment));
  }

  private PromptRequestFactory forkPromptFactory(ParentAgentSnapshot parent) {
    var segments = new ArrayList<>(parent.sentRequest().systemSegments());
    segments.add(FORK_RULES);
    return promptRequestFactory.withFixedSystemSegments(segments);
  }

  private ConversationManager forkConversation(
      ParentAgentSnapshot parent, SubAgentInvocation invocation) {
    var conversation = new ConversationManager();
    conversation.loadMessages(parent.sentRequest().history());
    parent.sentRequest().reminder().ifPresent(conversation::addMessage);
    if (!parent.assistantBlocks().isEmpty()) {
      conversation.addAssistantMessage(parent.assistantBlocks());
      var results = new ArrayList<ToolResultBlock>();
      for (ContentBlock block : parent.assistantBlocks()) {
        if (block instanceof ToolUseBlock tool) {
          String result =
              tool.toolUseId().equals(invocation.toolUseId())
                  ? "Fork 工作进程接管该任务。"
                  : "父 Agent 将继续处理该调用。";
          results.add(new ToolResultBlock(tool.toolUseId(), result, false));
        }
      }
      if (!results.isEmpty()) conversation.addToolResults(results);
    }
    return conversation;
  }

  private static SubAgentSpec specForPolicy(SubAgentSpec spec) {
    if (spec != null) return spec;
    return new SubAgentSpec(
        "fork",
        "fork",
        null,
        java.util.Set.of(),
        FORK_RULES,
        Integer.MAX_VALUE,
        "inherit",
        SubAgentSpec.PermissionMode.DONT_ASK,
        SubAgentSpec.Source.BUILTIN,
        Path.of("/runtime/fork.md"));
  }

  private ProviderRouter.Route resolveRoute(ProviderRouter.Route parent, String model) {
    if (model == null || model.isBlank() || "inherit".equalsIgnoreCase(model)) return parent;
    if (providerRouter != null) return providerRouter.selectModel(model).orElse(null);
    return parent.config() != null
            && (model.equalsIgnoreCase(parent.config().getModel())
                || model.equalsIgnoreCase(parent.config().getName()))
        ? parent
        : null;
  }

  private static ToolResult asyncResult(String taskId) {
    return ToolResult.success("{\"status\":\"async_launched\",\"task_id\":\"" + taskId + "\"}")
        .withMetadata(Map.of("status", "async_launched", "task_id", taskId));
  }

  public record SubAgentInvocation(
      String prompt,
      String description,
      String subagentType,
      String model,
      boolean runInBackground,
      String toolUseId) {
    public SubAgentInvocation {
      prompt = requireText(prompt, "prompt");
      description = requireText(description, "description");
      subagentType =
          subagentType == null ? "" : subagentType.strip().toLowerCase(java.util.Locale.ROOT);
      model =
          model == null || model.isBlank()
              ? null
              : model.strip().toLowerCase(java.util.Locale.ROOT);
      toolUseId = toolUseId == null ? "" : toolUseId;
    }

    public static SubAgentInvocation from(String toolUseId, Map<String, Object> input) {
      String prompt = input == null ? null : value(input.get("prompt"));
      String description = input == null ? null : value(input.get("description"));
      String type = input == null ? null : value(input.get("subagent_type"));
      String model = input == null ? null : value(input.get("model"));
      boolean background = input != null && Boolean.TRUE.equals(input.get("run_in_background"));
      return new SubAgentInvocation(prompt, description, type, model, background, toolUseId);
    }

    private static String value(Object value) {
      return value instanceof String text ? text : null;
    }
  }

  public record ParentAgentSnapshot(
      PromptRequest sentRequest,
      List<ContentBlock> assistantBlocks,
      ToolPolicy toolPolicy,
      ProviderRouter.Route route,
      AgentMode mode,
      AgentRun parentRun,
      String sessionId,
      com.mewcode.tool.ToolExecutionContext dispatchContext) {
    public ParentAgentSnapshot(
        PromptRequest sentRequest,
        List<ContentBlock> assistantBlocks,
        ToolPolicy toolPolicy,
        ProviderRouter.Route route,
        AgentMode mode,
        AgentRun parentRun,
        String sessionId) {
      this(sentRequest, assistantBlocks, toolPolicy, route, mode, parentRun, sessionId, null);
    }

    public ParentAgentSnapshot {
      sentRequest = Objects.requireNonNull(sentRequest, "sentRequest");
      assistantBlocks = List.copyOf(assistantBlocks == null ? List.of() : assistantBlocks);
      toolPolicy = Objects.requireNonNull(toolPolicy, "toolPolicy");
      route = Objects.requireNonNull(route, "route");
      mode = mode == null ? AgentMode.EXECUTE : mode;
      parentRun = Objects.requireNonNull(parentRun, "parentRun");
      sessionId = requireText(sessionId, "sessionId");
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
    return value.strip();
  }

  private static String safeMessage(Throwable error) {
    if (error == null) return "未知错误";
    return error.getMessage() == null || error.getMessage().isBlank()
        ? error.getClass().getSimpleName()
        : error.getMessage();
  }
}
