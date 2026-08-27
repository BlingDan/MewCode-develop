package com.mewcode.tool;

import com.mewcode.agent.AgentMode;
import com.mewcode.agent.CancellationToken;
import com.mewcode.agent.ToolPolicy;
import com.mewcode.permission.PermissionCheck;
import com.mewcode.permission.PermissionContext;
import com.mewcode.permission.PermissionDecision;
import com.mewcode.permission.PermissionRequest;
import com.mewcode.permission.PermissionResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

  /** 使用 Execute Mode 的默认策略执行一次工具调用。 */
  public ToolInvocationResult executeSingle(ToolCall call) {
    return executeSingle(call, ToolPolicy.forMode(AgentMode.EXECUTE), new CancellationToken());
  }

  /** 执行一次工具调用：先做模式和参数校验，再在可取消任务中执行并轮询等待。 未知工具、禁止调用、超时和运行时异常都会变成模型可见的错误结果。 */
  public ToolInvocationResult executeSingle(
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
    long started = System.nanoTime();
    if (permissions == null || permissionGate == null) {
      return result(call, ToolResult.error("权限运行时未初始化，工具调用已安全拒绝。"), started, null);
    }
    CancellationToken token = permissions.cancellationToken();
    if (token.isCancelled()) return cancelled(call, started, null);
    Tool tool = registry.get(call.toolName()).orElse(null);
    if (tool == null) {
      return result(
          call, ToolResult.error("未知工具：" + call.toolName() + "。请从当前可用工具列表中选择工具。"), started, null);
    }

    PermissionCheck check = permissionGate.check(call, tool, permissions);
    if (check.decision() == PermissionDecision.DENY) {
      return result(call, ToolResult.error(check.message()), started, tool);
    }
    boolean externalPathAuthorized = check.pathOutside();
    if (check.decision() == PermissionDecision.ASK) {
      PermissionRequest request = requestFor(call, check);
      PermissionResponse response;
      try {
        response = permissions.permissionBroker().await(request, token);
      } catch (RuntimeException error) {
        return result(call, ToolResult.error("权限确认失败，操作未执行：" + safeMessage(error)), started, tool);
      }
      if (response == null || response == PermissionResponse.DENY) {
        return result(call, ToolResult.error("操作被用户拒绝：" + check.message()), started, tool);
      }
      if (response == PermissionResponse.ALLOW_SESSION) {
        permissions.ruleEngine().addSessionGrant(check.authorizationKey());
      } else if (response == PermissionResponse.ALLOW_ALWAYS) {
        try {
          permissions.pathAuthorizationStore().grantAlways(check.authorizationKey());
        } catch (java.io.IOException error) {
          return result(
              call, ToolResult.error("永久授权保存失败，操作未执行：" + safeMessage(error)), started, tool);
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
      if (!isPermissionSafe(current, permissions)) {
        results.add(permissionDuplicateAware(current, seenIds, permissions));
        index++;
        continue;
      }
      int end = index;
      while (end < calls.size() && isPermissionSafe(calls.get(end), permissions)) end++;
      var futures = new ArrayList<Future<ToolInvocationResult>>(end - index);
      for (int i = index; i < end; i++) {
        ToolCall call = calls.get(i);
        if (!seenIds.add(call.toolUseId())) {
          futures.add(executor.submit(() -> duplicateResult(call)));
        } else {
          futures.add(executor.submit(() -> executeSingle(call, permissions)));
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
      ToolCall call, Set<String> seenIds, PermissionContext permissions) {
    if (!seenIds.add(call.toolUseId())) return duplicateResult(call);
    return executeSingle(call, permissions);
  }

  private boolean isPermissionSafe(ToolCall call, PermissionContext permissions) {
    return registry
        .get(call.toolName())
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
        results.add(duplicateAware(current, seenIds, policy, token));
        index++;
        continue;
      }

      int end = index;
      while (end < calls.size() && isSafe(calls.get(end), policy)) end++;
      var futures = new ArrayList<Future<ToolInvocationResult>>(end - index);
      for (int i = index; i < end; i++) {
        ToolCall call = calls.get(i);
        if (!seenIds.add(call.toolUseId())) {
          futures.add(executor.submit(() -> duplicateResult(call)));
        } else {
          futures.add(executor.submit(() -> executeSingle(call, policy, token)));
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
      ToolCall call, Set<String> seenIds, ToolPolicy policy, CancellationToken token) {
    if (!seenIds.add(call.toolUseId())) return duplicateResult(call);
    return executeSingle(call, policy, token);
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
        return result(call, ToolResult.error("工具批次执行超时，请缩小输入范围后重试。"), started, null);
      }
      try {
        return future.get(
            Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), POLL_MILLIS), TimeUnit.MILLISECONDS);
      } catch (TimeoutException ignored) {
        // 定期检查取消信号，避免等待完整工具超时。
      } catch (InterruptedException error) {
        future.cancel(true);
        Thread.currentThread().interrupt();
        return result(call, ToolResult.error("工具批次被中断，请稍后重试。"), started, null);
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
            ToolResult.error(
                "工具执行超时（限制 " + baseContext.timeout().toSeconds() + " 秒）。请缩小输入范围或调整参数后重试。"),
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
            : result(call, ToolResult.error("工具执行被中断，请稍后重试。"), started, tool);
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
    return new ToolInvocationResult(
        call.toolUseId(), new ToolResult(raw.content(), raw.isError(), metadata));
  }

  private ToolInvocationResult cancelled(ToolCall call, long started, Tool tool) {
    return result(call, ToolResult.error("工具执行已取消。"), started, tool);
  }

  private static ToolInvocationResult duplicateResult(ToolCall call) {
    return new ToolInvocationResult(
        call.toolUseId(), ToolResult.error("工具调用 ID 重复：" + call.toolUseId() + "。请重新发起唯一 ID 的调用。"));
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
}
