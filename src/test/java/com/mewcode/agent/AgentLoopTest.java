package com.mewcode.agent;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolCall;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolExecutor;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {

    @TempDir Path tempDir;

    @Test
    void continuesReActRoundsUntilModelStopsRequestingTools() throws Exception {
        var client = new QueueClient(List.of(
                queue(new StreamEvent.ToolCallComplete("call-1", "Echo", Map.of("value", "one")),
                        new StreamEvent.Usage(OptionalLong.of(1), OptionalLong.of(2)),
                        new StreamEvent.StreamEnd("tool_use")),
                queue(new StreamEvent.ToolCallComplete("call-2", "Echo", Map.of("value", "two")),
                        new StreamEvent.Usage(OptionalLong.of(3), OptionalLong.of(4)),
                        new StreamEvent.StreamEnd("tool_use")),
                queue(new StreamEvent.TextDelta("Finished."),
                        new StreamEvent.Usage(OptionalLong.of(5), OptionalLong.of(6)),
                        new StreamEvent.StreamEnd("end_turn"))));
        var registry = new ToolRegistry();
        registry.register(new EchoTool());

        try (var executor = new ToolExecutor(registry,
                new ToolExecutionContext(tempDir, java.time.Duration.ofSeconds(2), new FileStateCache()))) {
            var coordinator = new AgentTurnCoordinator(client, registry, executor,
                    new ConversationManager(), ToolApiProtocol.OPENAI,
                    new AgentLoopConfig(5, 3));
            var events = collect(coordinator.startRun("do it", AgentMode.EXECUTE));

            assertEquals(3, client.calls.get());
            assertEquals(3, events.stream().filter(event -> event instanceof AgentEvent.TurnComplete).count());
            assertEquals(2, events.stream().filter(event -> event instanceof AgentEvent.ToolUse).count());
            assertEquals(2, events.stream().filter(event -> event instanceof AgentEvent.ToolResult).count());
            var usage = events.stream()
                    .filter(event -> event instanceof AgentEvent.Usage)
                    .map(event -> (AgentEvent.Usage) event)
                    .reduce((first, second) -> second)
                    .orElseThrow();
            assertEquals(OptionalLong.of(9), usage.inputTokens());
            assertEquals(OptionalLong.of(12), usage.outputTokens());
            assertInstanceOf(AgentEvent.LoopComplete.class, events.getLast());
            assertEquals(3, ((AgentEvent.LoopComplete) events.getLast()).totalRounds());
            assertEquals(6, coordinator.conversation().getMessages().size());
        }
    }

    @Test
    void stopsAtConfiguredMaximumWithoutMakingAnotherModelRequest() throws Exception {
        var client = new QueueClient(List.of(
                queue(new StreamEvent.ToolCallComplete("call-1", "Echo", Map.of()),
                        new StreamEvent.StreamEnd("tool_use")),
                queue(new StreamEvent.ToolCallComplete("call-2", "Echo", Map.of()),
                        new StreamEvent.StreamEnd("tool_use"))));
        var registry = new ToolRegistry();
        registry.register(new EchoTool());

        try (var executor = new ToolExecutor(registry,
                new ToolExecutionContext(tempDir, java.time.Duration.ofSeconds(2), new FileStateCache()))) {
            var coordinator = new AgentTurnCoordinator(client, registry, executor,
                    new ConversationManager(), ToolApiProtocol.OPENAI,
                    new AgentLoopConfig(2, 3));
            var events = collect(coordinator.startRun("keep going", AgentMode.EXECUTE));

            assertEquals(2, client.calls.get());
            assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.Error
                    && ((AgentEvent.Error) event).message().contains("最大迭代")));
            assertEquals(2, ((AgentEvent.LoopComplete) events.getLast()).totalRounds());
        }
    }

    @Test
    void stopsAfterThreeRoundsWithoutAnExecutableKnownTool() throws Exception {
        var client = new QueueClient(List.of(
                queue(new StreamEvent.ToolCallComplete("unknown-1", "Missing", Map.of()),
                        new StreamEvent.StreamEnd("tool_use")),
                queue(new StreamEvent.ToolCallComplete("unknown-2", "Missing", Map.of()),
                        new StreamEvent.StreamEnd("tool_use")),
                queue(new StreamEvent.ToolCallComplete("unknown-3", "Missing", Map.of()),
                        new StreamEvent.StreamEnd("tool_use"))));
        var registry = new ToolRegistry();
        registry.register(new EchoTool());

        try (var executor = new ToolExecutor(registry,
                new ToolExecutionContext(tempDir, java.time.Duration.ofSeconds(2), new FileStateCache()))) {
            var coordinator = new AgentTurnCoordinator(client, registry, executor,
                    new ConversationManager(), ToolApiProtocol.OPENAI,
                    new AgentLoopConfig(5, 3));
            var events = collect(coordinator.startRun("find a tool", AgentMode.EXECUTE));

            assertEquals(3, client.calls.get());
            assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.Error
                    && ((AgentEvent.Error) event).message().contains("没有可执行")));
            assertEquals(3, ((AgentEvent.LoopComplete) events.getLast()).totalRounds());
        }
    }

    @Test
    void planModeFiltersRequestsAndRejectsAWriteRequestedByTheModel() throws Exception {
        var client = new QueueClient(List.of(
                queue(new StreamEvent.ToolCallComplete("write-1", "WriteLike",
                                Map.of("value", "blocked")),
                        new StreamEvent.StreamEnd("tool_use")),
                queue(new StreamEvent.TextDelta("Plan ready."),
                        new StreamEvent.StreamEnd("end_turn"))));
        var registry = new ToolRegistry();
        registry.register(new EchoTool());
        var writeInvoked = new AtomicBoolean();
        registry.register(new WriteLikeTool(writeInvoked));

        try (var executor = new ToolExecutor(registry,
                new ToolExecutionContext(tempDir, java.time.Duration.ofSeconds(2), new FileStateCache()))) {
            var coordinator = new AgentTurnCoordinator(client, registry, executor,
                    new ConversationManager(), ToolApiProtocol.OPENAI,
                    new AgentLoopConfig(5, 3));
            var events = collect(coordinator.startRun("plan it", AgentMode.PLAN));

            assertEquals(2, client.calls.get());
            var requestedNames = client.toolRequests.getFirst().stream()
                    .map(definition -> ((Map<?, ?>) definition.get("function")).get("name"))
                    .toList();
            assertEquals(List.of("Echo"), requestedNames);
            var result = events.stream()
                    .filter(event -> event instanceof AgentEvent.ToolResult)
                    .map(event -> (AgentEvent.ToolResult) event)
                    .findFirst()
                    .orElseThrow();
            assertTrue(result.isError());
            assertFalse(writeInvoked.get());
        }
    }

    @Test
    void forwardsTheCurrentModePromptToEachProviderRequest() throws Exception {
        var client = new QueueClient(List.of(queue(
                new StreamEvent.TextDelta("Plan ready."),
                new StreamEvent.StreamEnd("end_turn"))));
        var registry = new ToolRegistry();
        registry.register(new EchoTool());

        try (var executor = new ToolExecutor(registry,
                new ToolExecutionContext(tempDir, java.time.Duration.ofSeconds(2), new FileStateCache()))) {
            var coordinator = new AgentTurnCoordinator(client, registry, executor,
                    new ConversationManager(), ToolApiProtocol.OPENAI,
                    new AgentLoopConfig(), mode -> mode == AgentMode.PLAN
                            ? "plan-system" : "execute-system");
            collect(coordinator.startRun("make a plan", AgentMode.PLAN));

            assertEquals(List.of("plan-system"), client.prompts);
        }
    }

    @Test
    void cancellingDuringToolExecutionPairsTheToolResultAndStopsNextRequest() throws Exception {
        var client = new QueueClient(List.of(queue(
                new StreamEvent.ToolCallComplete("blocking-1", "Blocking",
                        Map.of("value", "wait")),
                new StreamEvent.StreamEnd("tool_use"))));
        var registry = new ToolRegistry();
        var started = new CountDownLatch(1);
        registry.register(new BlockingTool(started));
        var conversation = new ConversationManager();

        try (var executor = new ToolExecutor(registry,
                new ToolExecutionContext(tempDir, java.time.Duration.ofSeconds(20), new FileStateCache()))) {
            var coordinator = new AgentTurnCoordinator(client, registry, executor,
                    conversation, ToolApiProtocol.OPENAI);
            var run = coordinator.startRun("cancel tool", AgentMode.EXECUTE);
            assertTrue(started.await(2, TimeUnit.SECONDS));

            assertTrue(run.cancel());
            var events = collect(run);

            assertEquals(1, client.calls.get());
            var result = events.stream()
                    .filter(event -> event instanceof AgentEvent.ToolResult)
                    .map(event -> (AgentEvent.ToolResult) event)
                    .findFirst()
                    .orElseThrow();
            assertTrue(result.isError());
            assertTrue(result.result().contains("取消"));
            assertInstanceOf(AgentEvent.LoopComplete.class, events.getLast());
            assertEquals(3, conversation.getMessages().size());
            assertInstanceOf(com.mewcode.conversation.ToolUseBlock.class,
                    conversation.getMessages().get(1).content().getFirst());
            assertInstanceOf(com.mewcode.conversation.ToolResultBlock.class,
                    conversation.getMessages().get(2).content().getFirst());
        }
    }

    private static List<AgentEvent> collect(AgentRun run) throws Exception {
        var result = new ArrayList<AgentEvent>();
        while (true) {
            AgentEvent event = run.events().next();
            assertNotNull(event, "agent loop timed out");
            result.add(event);
            if (event instanceof AgentEvent.LoopComplete) return result;
        }
    }

    private static BlockingQueue<StreamEvent> queue(StreamEvent... events) {
        var queue = new LinkedBlockingQueue<StreamEvent>();
        queue.addAll(List.of(events));
        return queue;
    }

    private static final class QueueClient implements LlmClient {
        private final List<BlockingQueue<StreamEvent>> responses;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<List<Map<String, Object>>> toolRequests = new ArrayList<>();
        private final List<String> prompts = new ArrayList<>();

        private QueueClient(List<BlockingQueue<StreamEvent>> responses) {
            this.responses = new ArrayList<>(responses);
        }

        @Override
        public synchronized CancellableLlmStream openStream(
                ConversationManager conversation,
                List<Map<String, Object>> tools) {
            calls.incrementAndGet();
            toolRequests.add(tools == null ? List.of() : List.copyOf(tools));
            if (responses.isEmpty()) throw new AssertionError("unexpected extra model request");
            return new CancellableLlmStream(responses.removeFirst(), () -> { });
        }

        @Override
        public synchronized CancellableLlmStream openStream(
                ConversationManager conversation,
                List<Map<String, Object>> tools,
                String systemPrompt) {
            prompts.add(systemPrompt);
            return openStream(conversation, tools);
        }
    }

    private static final class EchoTool implements Tool {
        @Override public String name() { return "Echo"; }
        @Override public String description() { return "test echo"; }
        @Override public ToolCategory category() { return ToolCategory.SEARCH; }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        @Override public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
            return ToolResult.success("echo:" + input.getOrDefault("value", "ok"));
        }
        @Override public boolean isReadOnly() { return true; }
        @Override public boolean isDestructive() { return false; }
        @Override public boolean isConcurrencySafe(Map<String, Object> input) { return true; }
        @Override public String validateInput(Map<String, Object> input) { return null; }
    }

    private static final class WriteLikeTool implements Tool {
        private final AtomicBoolean invoked;

        private WriteLikeTool(AtomicBoolean invoked) {
            this.invoked = invoked;
        }

        @Override public String name() { return "WriteLike"; }
        @Override public String description() { return "test write"; }
        @Override public ToolCategory category() { return ToolCategory.FILE; }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        @Override public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
            invoked.set(true);
            return ToolResult.success("should not run");
        }
        @Override public boolean isReadOnly() { return false; }
        @Override public boolean isDestructive() { return true; }
        @Override public boolean isConcurrencySafe(Map<String, Object> input) { return false; }
        @Override public String validateInput(Map<String, Object> input) { return null; }
    }

    private static final class BlockingTool implements Tool {
        private final CountDownLatch started;

        private BlockingTool(CountDownLatch started) {
            this.started = started;
        }

        @Override public String name() { return "Blocking"; }
        @Override public String description() { return "test blocking tool"; }
        @Override public ToolCategory category() { return ToolCategory.SHELL; }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        @Override public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
            started.countDown();
            try {
                while (!context.cancellationToken().isCancelled()) {
                    Thread.sleep(1_000);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.error("工具执行已取消。");
        }
        @Override public boolean isReadOnly() { return true; }
        @Override public boolean isDestructive() { return false; }
        @Override public boolean isConcurrencySafe(Map<String, Object> input) { return false; }
        @Override public String validateInput(Map<String, Object> input) { return null; }
    }
}
