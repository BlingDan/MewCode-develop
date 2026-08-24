package com.mewcode.tool;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 负责工具校验、超时、错误隔离和批量调度。 */
public final class ToolExecutor implements AutoCloseable {

    private final ToolRegistry registry;
    private final ToolExecutionContext baseContext;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ToolExecutor(ToolRegistry registry, Path projectRoot, FileStateCache fileStateCache) {
        this(registry, new ToolExecutionContext(projectRoot.toAbsolutePath().normalize(), fileStateCache));
    }

    public ToolExecutor(ToolRegistry registry, ToolExecutionContext context) {
        this.registry = registry;
        this.baseContext = context;
    }

    public ToolInvocationResult executeSingle(ToolCall call) {
        long started = System.nanoTime();
        Tool tool = registry.get(call.toolName()).orElse(null);
        if (tool == null) {
            return result(call, ToolResult.error("未知工具：" + call.toolName()
                    + "。请从当前可用工具列表中选择工具。"), started, null);
        }
        String validation = safeValidate(tool, call.arguments());
        if (validation != null) {
            return result(call, ToolResult.error(validation), started, tool);
        }

        Future<ToolResult> future = executor.submit(() -> tool.execute(baseContext, call.arguments()));
        try {
            ToolResult toolResult = future.get(baseContext.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (toolResult == null) {
                toolResult = ToolResult.error("工具返回了空结果，请调整参数后重试。");
            }
            return result(call, toolResult, started, tool);
        } catch (TimeoutException error) {
            future.cancel(true);
            return result(call, ToolResult.error("工具执行超时（限制 "
                    + baseContext.timeout().toSeconds() + " 秒）。请缩小输入范围或调整参数后重试。"),
                    started, tool);
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return result(call, ToolResult.error("工具执行被中断，请稍后重试。"), started, tool);
        } catch (ExecutionException error) {
            future.cancel(true);
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return result(call, ToolResult.error("工具执行异常：" + safeMessage(cause)
                    + "。请调整参数后重试。"), started, tool);
        } catch (RuntimeException error) {
            future.cancel(true);
            return result(call, ToolResult.error("工具执行异常：" + safeMessage(error)
                    + "。请调整参数后重试。"), started, tool);
        }
    }

    public List<ToolInvocationResult> executeBatch(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) return List.of();
        var results = new ArrayList<ToolInvocationResult>(calls.size());
        var seenIds = new HashSet<String>();
        int index = 0;
        while (index < calls.size()) {
            ToolCall current = calls.get(index);
            boolean safe = isSafe(current);
            if (!safe) {
                results.add(duplicateAware(current, seenIds));
                index++;
                continue;
            }

            int end = index;
            while (end < calls.size() && isSafe(calls.get(end))) end++;
            var futures = new ArrayList<Future<ToolInvocationResult>>(end - index);
            for (int i = index; i < end; i++) {
                ToolCall call = calls.get(i);
                if (!seenIds.add(call.toolUseId())) {
                    futures.add(executor.submit(() -> duplicateResult(call)));
                } else {
                    futures.add(executor.submit(() -> executeSingle(call)));
                }
            }
            for (int i = 0; i < futures.size(); i++) {
                results.add(awaitBatchResult(futures.get(i), calls.get(index + i)));
            }
            index = end;
        }
        return List.copyOf(results);
    }

    private ToolInvocationResult duplicateAware(ToolCall call, Set<String> seenIds) {
        if (!seenIds.add(call.toolUseId())) return duplicateResult(call);
        return executeSingle(call);
    }

    private ToolInvocationResult awaitBatchResult(Future<ToolInvocationResult> future, ToolCall call) {
        try {
            return future.get(baseContext.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            return new ToolInvocationResult(call.toolUseId(), ToolResult.error(
                    "工具批次执行超时，请缩小输入范围后重试。"));
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new ToolInvocationResult(call.toolUseId(), ToolResult.error(
                    "工具批次被中断，请稍后重试。"));
        } catch (ExecutionException error) {
            return new ToolInvocationResult(call.toolUseId(), ToolResult.error(
                    "工具批次执行异常，请调整参数后重试。"));
        }
    }

    private boolean isSafe(ToolCall call) {
        return registry.get(call.toolName())
                .map(tool -> tool.isConcurrencySafe(call.arguments()))
                .orElse(false);
    }

    private String safeValidate(Tool tool, Map<String, Object> input) {
        try {
            return tool.validateInput(baseContext, input);
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
        return new ToolInvocationResult(call.toolUseId(),
                new ToolResult(raw.content(), raw.isError(), metadata));
    }

    private static ToolInvocationResult duplicateResult(ToolCall call) {
        return new ToolInvocationResult(call.toolUseId(), ToolResult.error(
                "工具调用 ID 重复：" + call.toolUseId() + "。请重新发起唯一 ID 的调用。"));
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        executor.close();
    }
}
