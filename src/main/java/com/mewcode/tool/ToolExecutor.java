package com.mewcode.tool;

import com.mewcode.agent.AgentMode;
import com.mewcode.agent.CancellationToken;
import com.mewcode.agent.ToolPolicy;
import com.mewcode.hook.HookEngine;
import com.mewcode.hook.HookEvent;
import com.mewcode.hook.HookInvocation;
import com.mewcode.hook.HookRejection;
import com.mewcode.hook.HookSessionState;
import com.mewcode.permission.PermissionCheck;
import com.mewcode.permission.PermissionContext;
import com.mewcode.permission.PermissionDecision;
import com.mewcode.permission.PermissionRequest;
import com.mewcode.permission.PermissionResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 负责工具校验、超时、取消、错误隔离和批量调度。
 *
 * <p>连续的安全调用会组成一个并发批次；有副作用或无法确认安全性的调用会形成 串行屏障。执行结果始终按输入调用顺序返回，避免并发只改变耗时而改变模型所见的 tool-result 顺序。
 */
public final class ToolExecutor implements AutoCloseable {

  private static final long POLL_MILLIS = 50;

  private final ToolRegistry registry;
  private final ToolExecutionContext baseContext;
  private final com.mewcode.permission.PermissionGate permissionGate;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private volatile HookEngine hookEngine;
  private volatile HookSessionState hookState;
  private volatile String hookSessionId;
  private final ThreadLocal<HookContext> hookContext = new ThreadLocal<>();

  public ToolExecutor(ToolRegistry registry, Path projectRoot, FileStateCache fileStateCache) {
    this(
        registry,
        new ToolExecutionContext(projectRoot.toAbsolutePath().normalize(), fileStateCache));
  }

  public ToolExecutor(
      ToolRegistry registry,
      Path projectRoot,
      FileStateCache fileStateCache,
      com.mewcode.permission.PermissionGate permissionGate) {
    this(
        registry,
        new ToolExecutionContext(projectRoot.toAbsolutePath().normalize(), fileStateCache),
        permissionGate);
  }

  public ToolExecutor(ToolRegistry registry, ToolExecutionContext context) {
    this(registry, context, null);
  }

  public ToolExecutor(
      ToolRegistry registry,
      ToolExecutionContext context,
      com.mewcode.permission.PermissionGate permissionGate) {
    this.registry = registry;
    this.baseContext = context;
    this.permissionGate = permissionGate;
  }

  /** 返回所有工具共享的项目根目录。 */
  public Path projectRoot() {
    return baseContext.projectRoot();
  }

  /** 绑定当前会话的 Hook；未绑定时保持旧的工具执行语义。 */
  public void configureHooks(HookEngine engine, HookSessionState state) {
    this.hookEngine = java.util.Objects.requireNonNull(engine, "engine");
    this.hookState = java.util.Objects.requireNonNull(state, "state");
  }

  /** 更新 Hook 事件中的会话标识。 */
  public void setHookSessionId(String sessionId) {
    hookSessionId = sessionId == null || sessionId.isBlank() ? null : sessionId;
  }

  /** 使用 Execute Mode 的默认策略执行一次工具调用。 */
  public ToolInvocationResult executeSingle(ToolCall call) {
    return executeSingle(call, ToolPolicy.forMode(AgentMode.EXECUTE), new CancellationToken());
  }

  /** 执行一次工具调用：先做模式和参数校验，再在可取消任务中执行并轮询等待。 未知工具、禁止调用、超时和运行时异常都会变成模型可见的错误结果。 */
  public ToolInvocationResult executeSingle(
      ToolCall call, ToolPolicy policy, CancellationToken token) {
    AgentMode mode = AgentMode.EXECUTE;
    HookContext context = newHookContext(defaultRequestId(), mode, token);
    Optional<HookRejection> rejection = prepareHook(call, context);
    if (rejection.isPresent()) {
      return withHookContext(
          context, () -> hookRejected(call, rejection.orElseThrow(), System.nanoTime()));
    }
    return executeSinglePrepared(call, policy, token, context);
  }

  private ToolInvocationResult executeSinglePrepared(
      ToolCall call, ToolPolicy policy, CancellationToken token, HookContext context) {
    return withHookContext(context, () -> executeSingleBody(call, policy, token));
  }

  private ToolInvocationResult executeSingleBody(
      ToolCall call, ToolPolicy policy, CancellationToken token) {
    long started = System.nanoTime();
    if (token.isCancelled()) return cancelled(call, started, null);

    Tool tool = registry.get(call.toolName()).orElse(null);
    if (tool == null) {
      return result(
          call, ToolResult.error("未知工具：" + call.toolName() + "。请从当前可用工具列表中选择工具。"), started, null);
    }
    if (!policy.isAllowed(tool)) {
      return result(
          call, ToolResult.error("当前模式不允许调用工具：" + call.toolName() + "。请先切换到执行模式。"), started, tool);
    }

    ToolExecutionContext context = baseContext.withCancellationToken(token);
    String validation = safeValidate(tool, context, call.arguments());
    if (validation != null) {
      return result(call, ToolResult.error(validation), started, tool);
    }

    Future<ToolResult> future =
        executor.submit(
            () -> {
              token.throwIfCancelled();
              return tool.execute(context, call.arguments());
            });
    return awaitSingle(future, call, tool, token, started);
  }

  /** 使用五层权限上下文执行一次调用；该入口用于新 Agent Run。 */
  public ToolInvocationResult executeSingle(ToolCall call, PermissionContext permissions) {
    AgentMode mode =
        permissions != null && permissions.mode() == com.mewcode.permission.PermissionMode.PLAN
            ? AgentMode.PLAN
            : AgentMode.EXECUTE;
    return executeSingle(call, ToolPolicy.forMode(mode), permissions);
  }

  /** 先应用本轮工具策略，再进入五层权限系统。 */
  public ToolInvocationResult executeSingle(
      ToolCall call, ToolPolicy policy, PermissionContext permissions) {
    AgentMode mode =
        permissions != null && permissions.mode() == com.mewcode.permission.PermissionMode.PLAN
            ? AgentMode.PLAN
            : AgentMode.EXECUTE;
    CancellationToken token =
        permissions == null ? new CancellationToken() : permissions.cancellationToken();
    HookContext context = newHookContext(defaultRequestId(), mode, token);
    Optional<HookRejection> rejection = prepareHook(call, context);
    if (rejection.isPresent()) {
      return withHookContext(
          context, () -> hookRejected(call, rejection.orElseThrow(), System.nanoTime()));
    }
    return executeSinglePermissionPrepared(call, policy, permissions, context);
  }

  private ToolInvocationResult executeSinglePermissionPrepared(
      ToolCall call, ToolPolicy policy, PermissionContext permissions, HookContext context) {
    return withHookContext(context, () -> executeSinglePermissionBody(call, policy, permissions));
  }

  private ToolInvocationResult executeSinglePermissionBody(
      ToolCall call, ToolPolicy policy, PermissionContext permissions) {
    long started = System.nanoTime();
    if (permissions == null || permissionGate == null) {
      return result(
          call, errorWithStatus("权限运行时未初始化，工具调用已安全拒绝。", "permission_error"), started, null);
    }
    CancellationToken token = permissions.cancellationToken();
    if (token.isCancelled()) return cancelled(call, started, null);
    Tool tool = registry.get(call.toolName()).orElse(null);
    if (tool == null) {
      return result(
          call, ToolResult.error("未知工具：" + call.toolName() + "。请从当前可用工具列表中选择工具。"), started, null);
    }
    if (policy == null || !policy.isAllowed(tool)) {
      return result(
          call, ToolResult.error("当前 Skill 或模式不允许调用工具：" + call.toolName() + "。"), started, tool);
    }

    PermissionCheck check = permissionGate.check(call, tool, permissions);
    if (check.decision() == PermissionDecision.DENY) {
      return result(call, errorWithStatus(check.message(), "permission_denied"), started, tool);
    }
    boolean externalPathAuthorized = check.pathOutside();
    if (check.decision() == PermissionDecision.ASK) {
      PermissionRequest request = requestFor(call, check);
      PermissionResponse response;
      try {
        response = permissions.permissionBroker().await(request, token);
      } catch (RuntimeException error) {
        return result(
            call,
            errorWithStatus("权限确认失败，操作未执行：" + safeMessage(error), "permission_error"),
            started,
            tool);
      }
      if (response == null || response == PermissionResponse.DENY) {
        return result(
            call,
            errorWithStatus("操作被用户拒绝：" + check.message(), "permission_denied"),
            started,
            tool);
      }
      if (response == PermissionResponse.ALLOW_SESSION) {
        permissions.ruleEngine().addSessionGrant(check.authorizationKey());
      } else if (response == PermissionResponse.ALLOW_ALWAYS) {
        try {
          permissions.pathAuthorizationStore().grantAlways(check.authorizationKey());
        } catch (java.io.IOException error) {
          return result(
              call,
              errorWithStatus("永久授权保存失败，操作未执行：" + safeMessage(error), "permission_error"),
              started,
              tool);
        }
      }
    }

    ToolExecutionContext context =
        baseContext.withPermissionContext(permissions, token, externalPathAuthorized);
    String validation = safeValidate(tool, context, call.arguments());
    if (validation != null) {
      return result(call, ToolResult.error(validation), started, tool);
    }
    Future<ToolResult> future =
        executor.submit(
            () -> {
              token.throwIfCancelled();
              return tool.execute(context, call.arguments());
            });
    return awaitSingle(future, call, tool, token, started);
  }

  /** 使用五层权限上下文执行一批调用；需要确认的调用按原始顺序串行处理。 */
  public List<ToolInvocationResult> executeBatch(
      List<ToolCall> calls, PermissionContext permissions) {
    AgentMode mode =
        permissions != null && permissions.mode() == com.mewcode.permission.PermissionMode.PLAN
            ? AgentMode.PLAN
            : AgentMode.EXECUTE;
    return executeBatch(calls, ToolPolicy.forMode(mode), permissions, defaultRequestId(), mode);
  }

  /** 使用同一 ToolPolicy 和权限快照执行一批调用。 */
  public List<ToolInvocationResult> executeBatch(
      List<ToolCall> calls, ToolPolicy policy, PermissionContext permissions) {
    AgentMode mode =
        permissions != null && permissions.mode() == com.mewcode.permission.PermissionMode.PLAN
            ? AgentMode.PLAN
            : AgentMode.EXECUTE;
    return executeBatch(calls, policy, permissions, defaultRequestId(), mode);
  }

  /** 使用指定请求上下文执行一批调用；Agent Loop 用此入口保证同一轮 request_id 稳定。 */
  public List<ToolInvocationResult> executeBatch(
      List<ToolCall> calls,
      ToolPolicy policy,
      PermissionContext permissions,
      String requestId,
      AgentMode mode) {
    CancellationToken token =
        permissions == null ? new CancellationToken() : permissions.cancellationToken();
    return prepareAndExecuteBatch(
        calls,
        policy,
        permissions,
        token,
        true,
        requestId == null || requestId.isBlank() ? defaultRequestId() : requestId,
        mode == null ? AgentMode.EXECUTE : mode);
  }

  private List<ToolInvocationResult> executeBatchWithPermission(
      List<ToolCall> calls, ToolPolicy policy, PermissionContext permissions, HookContext context) {
    if (calls == null || calls.isEmpty()) return List.of();
    if (permissions == null || permissionGate == null) {
      return calls.stream()
          .map(
              call ->
                  result(call, ToolResult.error("权限运行时未初始化，工具调用已安全拒绝。"), System.nanoTime(), null))
          .toList();
    }
    var results = new ArrayList<ToolInvocationResult>(calls.size());
    var seenIds = new HashSet<String>();
    int index = 0;
    while (index < calls.size()) {
      if (permissions.cancellationToken().isCancelled()) {
        for (int i = index; i < calls.size(); i++) {
          results.add(cancelled(calls.get(i), System.nanoTime(), null));
        }
        break;
      }
      ToolCall current = calls.get(index);
      if (!isPermissionSafe(current, policy, permissions)) {
        results.add(permissionDuplicateAware(current, seenIds, policy, permissions, context));
        index++;
        continue;
      }
      int end = index;
      while (end < calls.size() && isPermissionSafe(calls.get(end), policy, permissions)) end++;
      var futures = new ArrayList<Future<ToolInvocationResult>>(end - index);
      for (int i = index; i < end; i++) {
        ToolCall call = calls.get(i);
        if (!seenIds.add(call.toolUseId())) {
          futures.add(executor.submit(() -> withHookContext(context, () -> duplicateResult(call))));
        } else {
          futures.add(
              executor.submit(
                  () -> executeSinglePermissionPrepared(call, policy, permissions, context)));
        }
      }
      for (int i = 0; i < futures.size(); i++) {
        results.add(
            awaitBatchResult(
                futures.get(i), calls.get(index + i), futures, permissions.cancellationToken()));
      }
      index = end;
    }
    return List.copyOf(results);
  }

  private ToolInvocationResult permissionDuplicateAware(
      ToolCall call,
      Set<String> seenIds,
      ToolPolicy policy,
      PermissionContext permissions,
      HookContext context) {
    if (!seenIds.add(call.toolUseId()))
      return withHookContext(context, () -> duplicateResult(call));
    return executeSinglePermissionPrepared(call, policy, permissions, context);
  }

  private boolean isPermissionSafe(
      ToolCall call, ToolPolicy policy, PermissionContext permissions) {
    return registry
        .get(call.toolName())
        .filter(policy::isAllowed)
        .filter(tool -> tool.isConcurrencySafe(call.arguments()))
        .map(
            tool ->
                permissionGate.check(call, tool, permissions).decision()
                    == PermissionDecision.ALLOW)
        .orElse(false);
  }

  private static PermissionRequest requestFor(ToolCall call, PermissionCheck check) {
    String operation = "[" + call.toolName() + "] " + call.arguments();
    return new PermissionRequest(
        call.toolUseId(),
        call.toolName(),
        call.arguments(),
        operation,
        check.message(),
        check.authorizationKey());
  }

  /** 使用 Execute Mode 的默认策略执行一批调用。 */
  public List<ToolInvocationResult> executeBatch(List<ToolCall> calls) {
    return executeBatch(calls, ToolPolicy.forMode(AgentMode.EXECUTE), new CancellationToken());
  }

  /** 按安全性分批执行工具：同一安全批次可并发，副作用调用按模型顺序串行。 取消会取消当前批次所有 Future，并为尚未执行的调用补充取消结果。 */
  public List<ToolInvocationResult> executeBatch(
      List<ToolCall> calls, ToolPolicy policy, CancellationToken token) {
    return executeBatch(calls, policy, token, defaultRequestId(), AgentMode.EXECUTE);
  }

  /** 使用指定请求上下文执行无权限运行时的工具批次。 */
  public List<ToolInvocationResult> executeBatch(
      List<ToolCall> calls,
      ToolPolicy policy,
      CancellationToken token,
      String requestId,
      AgentMode mode) {
    return prepareAndExecuteBatch(
        calls,
        policy,
        null,
        token,
        false,
        requestId == null || requestId.isBlank() ? defaultRequestId() : requestId,
        mode == null ? AgentMode.EXECUTE : mode);
  }

  private List<ToolInvocationResult> executeBatchWithoutPermission(
      List<ToolCall> calls, ToolPolicy policy, CancellationToken token, HookContext context) {
    if (calls == null || calls.isEmpty()) return List.of();
    var results = new ArrayList<ToolInvocationResult>(calls.size());
    var seenIds = new HashSet<String>();
    int index = 0;
    while (index < calls.size()) {
      if (token.isCancelled()) {
        for (int i = index; i < calls.size(); i++) {
          results.add(cancelled(calls.get(i), System.nanoTime(), null));
        }
        break;
      }

      ToolCall current = calls.get(index);
      boolean safe = isSafe(current, policy);
      if (!safe) {
        results.add(duplicateAware(current, seenIds, policy, token, context));
        index++;
        continue;
      }

      int end = index;
      while (end < calls.size() && isSafe(calls.get(end), policy)) end++;
      var futures = new ArrayList<Future<ToolInvocationResult>>(end - index);
      for (int i = index; i < end; i++) {
        ToolCall call = calls.get(i);
        if (!seenIds.add(call.toolUseId())) {
          futures.add(executor.submit(() -> withHookContext(context, () -> duplicateResult(call))));
        } else {
          futures.add(executor.submit(() -> executeSinglePrepared(call, policy, token, context)));
        }
      }
      for (int i = 0; i < futures.size(); i++) {
        results.add(awaitBatchResult(futures.get(i), calls.get(index + i), futures, token));
      }
      index = end;
    }
    return List.copyOf(results);
  }

  private ToolInvocationResult duplicateAware(
      ToolCall call,
      Set<String> seenIds,
      ToolPolicy policy,
      CancellationToken token,
      HookContext context) {
    if (!seenIds.add(call.toolUseId()))
      return withHookContext(context, () -> duplicateResult(call));
    return executeSinglePrepared(call, policy, token, context);
  }

  private List<ToolInvocationResult> prepareAndExecuteBatch(
      List<ToolCall> calls,
      ToolPolicy policy,
      PermissionContext permissions,
      CancellationToken token,
      boolean permissionPath,
      String requestId,
      AgentMode mode) {
    if (calls == null || calls.isEmpty()) return List.of();
    HookContext context = newHookContext(requestId, mode, token);
    var ready = new ArrayList<ToolCall>(calls.size());
    var readyIndexes = new ArrayList<Integer>(calls.size());
    var results =
        new ArrayList<ToolInvocationResult>(java.util.Collections.nCopies(calls.size(), null));
    for (int index = 0; index < calls.size(); index++) {
      ToolCall call = calls.get(index);
      Optional<HookRejection> rejection = prepareHook(call, context);
      if (rejection.isPresent()) {
        results.set(
            index,
            withHookContext(
                context, () -> hookRejected(call, rejection.orElseThrow(), System.nanoTime())));
      } else {
        ready.add(call);
        readyIndexes.add(index);
      }
    }
    List<ToolInvocationResult> executed =
        withHookContext(
            context,
            () ->
                permissionPath
                    ? executeBatchWithPermission(ready, policy, permissions, context)
                    : executeBatchWithoutPermission(ready, policy, token, context));
    for (int index = 0; index < executed.size(); index++) {
      results.set(readyIndexes.get(index), executed.get(index));
    }
    return List.copyOf(results);
  }

  /** 等待并发批次中的一个槽位，同时周期性检查共享取消 token。 */
  private ToolInvocationResult awaitBatchResult(
      Future<ToolInvocationResult> future,
      ToolCall call,
      List<Future<ToolInvocationResult>> batch,
      CancellationToken token) {
    long started = System.nanoTime();
    long deadline = System.nanoTime() + baseContext.timeout().toNanos();
    while (true) {
      if (token.isCancelled()) {
        cancelAll(batch);
        return cancelled(call, started, null);
      }
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        future.cancel(true);
        return result(call, errorWithStatus("工具批次执行超时，请缩小输入范围后重试。", "timeout"), started, null);
      }
      try {
        return future.get(
            Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), POLL_MILLIS), TimeUnit.MILLISECONDS);
      } catch (TimeoutException ignored) {
        // 定期检查取消信号，避免等待完整工具超时。
      } catch (InterruptedException error) {
        future.cancel(true);
        Thread.currentThread().interrupt();
        return result(call, errorWithStatus("工具批次被中断，请稍后重试。", "interrupted"), started, null);
      } catch (ExecutionException error) {
        return result(call, ToolResult.error("工具批次执行异常，请调整参数后重试。"), started, null);
      }
    }
  }

  /** 等待单个工具 Future，并把取消、超时和异常转换为结构化结果。 */
  private ToolInvocationResult awaitSingle(
      Future<ToolResult> future, ToolCall call, Tool tool, CancellationToken token, long started) {
    long deadline = System.nanoTime() + baseContext.timeout().toNanos();
    while (true) {
      if (token.isCancelled()) {
        future.cancel(true);
        return cancelled(call, started, tool);
      }
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        future.cancel(true);
        return result(
            call,
            errorWithStatus(
                "工具执行超时（限制 " + baseContext.timeout().toSeconds() + " 秒）。请缩小输入范围或调整参数后重试。",
                "timeout"),
            started,
            tool);
      }
      try {
        ToolResult toolResult =
            future.get(
                Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), POLL_MILLIS),
                TimeUnit.MILLISECONDS);
        if (toolResult == null) {
          toolResult = ToolResult.error("工具返回了空结果，请调整参数后重试。");
        }
        return result(call, toolResult, started, tool);
      } catch (TimeoutException ignored) {
        // 定期检查取消和总超时。
      } catch (InterruptedException error) {
        future.cancel(true);
        Thread.currentThread().interrupt();
        return token.isCancelled()
            ? cancelled(call, started, tool)
            : result(call, errorWithStatus("工具执行被中断，请稍后重试。", "interrupted"), started, tool);
      } catch (ExecutionException error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return result(
            call, ToolResult.error("工具执行异常：" + safeMessage(cause) + "。请调整参数后重试。"), started, tool);
      } catch (RuntimeException error) {
        future.cancel(true);
        return result(
            call, ToolResult.error("工具执行异常：" + safeMessage(error) + "。请调整参数后重试。"), started, tool);
      }
    }
  }

  /** 只有已知且当前模式允许的工具才能参与安全并发批次。 */
  private boolean isSafe(ToolCall call, ToolPolicy policy) {
    return registry
        .get(call.toolName())
        .filter(policy::isAllowed)
        .map(tool -> tool.isConcurrencySafe(call.arguments()))
        .orElse(false);
  }

  /** 在权限预判前执行 PreToolUse；Skill 等特殊路径也复用此入口。 */
  public Optional<HookRejection> prepareHook(
      ToolCall call, String requestId, AgentMode mode, CancellationToken token) {
    return prepareHook(call, newHookContext(requestId, mode, token));
  }

  /** 为自定义工具路径发布唯一的 PostToolUse 终态。 */
  public void publishPostToolUse(
      ToolCall call,
      ToolInvocationResult result,
      String requestId,
      AgentMode mode,
      CancellationToken token) {
    publishPostHook(call, result, newHookContext(requestId, mode, token));
  }

  private Optional<HookRejection> prepareHook(ToolCall call, HookContext context) {
    if (hookEngine == null || hookState == null) return Optional.empty();
    var payload = hookPayload(context, call);
    return hookEngine.dispatch(
        new HookInvocation(HookEvent.PRE_TOOL_USE, payload, hookState, context.token()));
  }

  private ToolInvocationResult hookRejected(ToolCall call, HookRejection rejection, long started) {
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("status", "hook_rejected");
    metadata.put("hook_name", rejection.hookName());
    metadata.put("hook_reason", rejection.reason());
    return result(
        call,
        new ToolResult(
            "Hook [" + rejection.hookName() + "] 拒绝工具调用：" + rejection.reason(), true, metadata),
        started,
        registry.get(call.toolName()).orElse(null));
  }

  private void publishPostHook(
      ToolCall call, ToolInvocationResult invocationResult, HookContext context) {
    if (hookEngine == null || hookState == null || invocationResult == null) return;
    var result = invocationResult.result();
    var payload = hookPayload(context, call);
    payload.put("result", result.content());
    payload.put("is_error", result.isError());
    payload.put(
        "status", result.metadata().getOrDefault("status", result.isError() ? "error" : "success"));
    payload.put("duration_ms", result.metadata().getOrDefault("durationMs", 0L));
    hookEngine.dispatch(
        new HookInvocation(HookEvent.POST_TOOL_USE, payload, hookState, context.token()));
  }

  private LinkedHashMap<String, Object> hookPayload(HookContext context, ToolCall call) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("cwd", projectRoot().toString());
    if (hookSessionId != null) payload.put("session_id", hookSessionId);
    payload.put("request_id", context.requestId());
    payload.put("mode", context.mode().name());
    payload.put("tool_use_id", call.toolUseId());
    payload.put("tool_name", call.toolName());
    payload.put("tool_input", call.arguments());
    return payload;
  }

  private HookContext newHookContext(String requestId, AgentMode mode, CancellationToken token) {
    return new HookContext(
        requestId == null || requestId.isBlank() ? defaultRequestId() : requestId,
        mode == null ? AgentMode.EXECUTE : mode,
        token == null ? new CancellationToken() : token);
  }

  private String defaultRequestId() {
    return UUID.randomUUID().toString();
  }

  private <T> T withHookContext(HookContext context, java.util.function.Supplier<T> action) {
    HookContext previous = hookContext.get();
    hookContext.set(context);
    try {
      return action.get();
    } finally {
      if (previous == null) hookContext.remove();
      else hookContext.set(previous);
    }
  }

  private String safeValidate(
      Tool tool, ToolExecutionContext context, java.util.Map<String, Object> input) {
    try {
      return tool.validateInput(context, input);
    } catch (RuntimeException error) {
      return "工具参数校验失败：" + safeMessage(error) + "。请调整参数后重试。";
    }
  }

  private ToolInvocationResult result(ToolCall call, ToolResult raw, long started, Tool tool) {
    var metadata = new java.util.LinkedHashMap<String, Object>(raw.metadata());
    metadata.put("tool", call.toolName());
    if (tool != null) {
      metadata.put("category", tool.category().name().toLowerCase());
      metadata.put("readOnly", tool.isReadOnly());
      metadata.put("destructive", tool.isDestructive());
    }
    metadata.put("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
    metadata.putIfAbsent("status", raw.isError() ? "error" : "success");
    ToolInvocationResult result =
        new ToolInvocationResult(
            call.toolUseId(), new ToolResult(raw.content(), raw.isError(), metadata));
    HookContext context = hookContext.get();
    if (context != null) publishPostHook(call, result, context);
    return result;
  }

  private ToolInvocationResult cancelled(ToolCall call, long started, Tool tool) {
    return result(call, errorWithStatus("工具执行已取消。", "cancelled"), started, tool);
  }

  private static ToolResult errorWithStatus(String message, String status) {
    return ToolResult.error(message).withMetadata(Map.of("status", status));
  }

  private ToolInvocationResult duplicateResult(ToolCall call) {
    return result(
        call,
        ToolResult.error("工具调用 ID 重复：" + call.toolUseId() + "。请重新发起唯一 ID 的调用。")
            .withMetadata(Map.of("status", "duplicate")),
        System.nanoTime(),
        registry.get(call.toolName()).orElse(null));
  }

  private static void cancelAll(List<Future<ToolInvocationResult>> futures) {
    for (Future<ToolInvocationResult> future : futures) future.cancel(true);
  }

  private static String safeMessage(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
  }

  /** 关闭虚拟线程执行器，应用退出时释放仍在等待的工具任务。 */
  @Override
  public void close() {
    executor.close();
  }

  private record HookContext(String requestId, AgentMode mode, CancellationToken token) {}
}
