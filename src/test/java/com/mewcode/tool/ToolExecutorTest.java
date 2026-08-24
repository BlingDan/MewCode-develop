package com.mewcode.tool;

import com.mewcode.tool.impl.EditFileTool;
import com.mewcode.tool.impl.GlobTool;
import com.mewcode.tool.impl.GrepTool;
import com.mewcode.tool.impl.ReadFileTool;
import com.mewcode.tool.impl.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorTest {

    @TempDir Path tempDir;

    @Test
    void validatesBeforeExecutionAndReturnsStructuredError() {
        var calls = new AtomicInteger();
        var registry = new ToolRegistry();
        registry.register(new TestTool("Validate", true, calls, null, 0,
                "参数不合法，请调整后重试。"));

        try (var executor = new ToolExecutor(registry, context())) {
            ToolInvocationResult result = executor.executeSingle(
                    new ToolCall("call-1", "Validate", Map.of()));
            assertTrue(result.result().isError());
            assertEquals("call-1", result.toolUseId());
            assertTrue(result.result().content().contains("参数不合法"));
            assertEquals(0, calls.get());
            assertEquals("Validate", result.result().metadata().get("tool"));
            assertTrue(result.result().metadata().containsKey("durationMs"));
        }
    }

    @Test
    void validatesRelativePathsAgainstTheSharedRootBeforeAnyToolRuns() {
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new EditFileTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());

        List<ToolCall> calls = List.of(
                new ToolCall("read", "ReadFile", Map.of("path", ".trae/skills/mew-spec/SKILL.md")),
                new ToolCall("write", "WriteFile", Map.of("path", "new.txt", "content", "x")),
                new ToolCall("edit", "EditFile", Map.of(
                        "path", "README.md", "old_string", "old", "new_string", "new")),
                new ToolCall("glob", "Glob", Map.of("pattern", "**/*.java")),
                new ToolCall("grep", "Grep", Map.of("path", ".", "pattern", "needle")));

        try (var executor = new ToolExecutor(registry, context())) {
            for (ToolCall call : calls) {
                ToolInvocationResult result = executor.executeSingle(call);
                assertTrue(result.result().isError(), call.toolName());
                assertTrue(result.result().content().contains("当前项目根目录"), result.result().content());
                assertTrue(result.result().content().contains(tempDir.toAbsolutePath().normalize().toString()),
                        result.result().content());
            }
        }
        assertFalse(java.nio.file.Files.exists(tempDir.resolve("new.txt")));
    }

    @Test
    void runsSafeCallsConcurrentlyAndPreservesInputOrder() throws Exception {
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var registry = new ToolRegistry();
        registry.register(new TestTool("Safe", true, new AtomicInteger(), started, 0,
                null, release));

        try (var executor = new ToolExecutor(registry, context())) {
            try (var waiter = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<List<ToolInvocationResult>> future = waiter.submit(() -> executor.executeBatch(List.of(
                        new ToolCall("first", "Safe", Map.of()),
                        new ToolCall("second", "Safe", Map.of()))));
                assertTrue(started.await(1, TimeUnit.SECONDS), "safe calls did not start together");
                release.countDown();
                List<ToolInvocationResult> results = future.get(2, TimeUnit.SECONDS);
                assertEquals(List.of("first", "second"),
                        results.stream().map(ToolInvocationResult::toolUseId).toList());
            }
        }
    }

    @Test
    void duplicateIdsAndUnknownToolsRemainPairedWithTheirCalls() {
        var registry = new ToolRegistry();
        registry.register(new TestTool("Safe", true, new AtomicInteger(), null, 0, null));

        try (var executor = new ToolExecutor(registry, context())) {
            List<ToolInvocationResult> results = executor.executeBatch(List.of(
                    new ToolCall("same", "Safe", Map.of()),
                    new ToolCall("same", "Safe", Map.of()),
                    new ToolCall("missing", "Missing", Map.of())));

            assertEquals(List.of("same", "same", "missing"),
                    results.stream().map(ToolInvocationResult::toolUseId).toList());
            assertTrue(results.get(1).result().isError());
            assertTrue(results.get(1).result().content().contains("重复"));
            assertTrue(results.get(2).result().isError());
            assertTrue(results.get(2).result().content().contains("未知工具"));
        }
    }

    @Test
    void serialToolsNeverOverlap() {
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        var registry = new ToolRegistry();
        registry.register(new SerialTool(active, maximum));

        try (var executor = new ToolExecutor(registry, context())) {
            List<ToolInvocationResult> results = executor.executeBatch(List.of(
                    new ToolCall("one", "Serial", Map.of()),
                    new ToolCall("two", "Serial", Map.of())));

            assertEquals(List.of("one", "two"),
                    results.stream().map(ToolInvocationResult::toolUseId).toList());
            assertEquals(1, maximum.get());
        }
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(tempDir, Duration.ofSeconds(2), new FileStateCache());
    }

    private static final class TestTool implements Tool {
        private final String name;
        private final boolean safe;
        private final AtomicInteger executions;
        private final CountDownLatch started;
        private final int delayMillis;
        private final String validation;
        private final CountDownLatch release;

        private TestTool(String name, boolean safe, AtomicInteger executions,
                         CountDownLatch started, int delayMillis, String validation) {
            this(name, safe, executions, started, delayMillis, validation, null);
        }

        private TestTool(String name, boolean safe, AtomicInteger executions,
                         CountDownLatch started, int delayMillis, String validation,
                         CountDownLatch release) {
            this.name = name;
            this.safe = safe;
            this.executions = executions;
            this.started = started;
            this.delayMillis = delayMillis;
            this.validation = validation;
            this.release = release;
        }

        @Override
        public String name() { return name; }

        @Override
        public String description() { return name; }

        @Override
        public ToolCategory category() { return ToolCategory.SEARCH; }

        @Override
        public Map<String, Object> inputSchema() { return Map.of("type", "object"); }

        @Override
        public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
            executions.incrementAndGet();
            if (started != null) started.countDown();
            if (release != null) {
                try {
                    release.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error("interrupted");
                }
            }
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error("interrupted");
                }
            }
            return ToolResult.success("ok");
        }

        @Override
        public boolean isReadOnly() { return true; }

        @Override
        public boolean isDestructive() { return false; }

        @Override
        public boolean isConcurrencySafe(Map<String, Object> input) { return safe; }

        @Override
        public String validateInput(Map<String, Object> input) { return validation; }
    }

    private static final class SerialTool implements Tool {
        private final AtomicInteger active;
        private final AtomicInteger maximum;

        private SerialTool(AtomicInteger active, AtomicInteger maximum) {
            this.active = active;
            this.maximum = maximum;
        }

        @Override
        public String name() { return "Serial"; }

        @Override
        public String description() { return "serial test tool"; }

        @Override
        public ToolCategory category() { return ToolCategory.FILE; }

        @Override
        public Map<String, Object> inputSchema() { return Map.of("type", "object"); }

        @Override
        public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(25);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return ToolResult.error("interrupted");
            } finally {
                active.decrementAndGet();
            }
            return ToolResult.success("ok");
        }

        @Override
        public boolean isReadOnly() { return false; }

        @Override
        public boolean isDestructive() { return false; }

        @Override
        public boolean isConcurrencySafe(Map<String, Object> input) { return false; }

        @Override
        public String validateInput(Map<String, Object> input) { return null; }
    }
}
