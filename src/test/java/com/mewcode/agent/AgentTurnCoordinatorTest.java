package com.mewcode.agent;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentTurnCoordinatorTest {

    @TempDir Path tempDir;

    @Test
    void sendsAssistantToolUsesAndAllToolResultsInSinglePairedMessages() throws Exception {
        var first = queue(
                new StreamEvent.TextDelta("I will inspect both.\n"),
                new StreamEvent.ToolCallComplete("call-read", "Echo", Map.of("value", "one")),
                new StreamEvent.ToolCallComplete("call-search", "Echo", Map.of("value", "two")),
                new StreamEvent.StreamEnd("tool_use"));
        var second = queue(new StreamEvent.TextDelta("Done."), new StreamEvent.StreamEnd("end_turn"));
        var client = new QueueClient(List.of(first, second));
        var registry = new ToolRegistry();
        registry.register(new EchoTool());
        var conversation = new ConversationManager();

        try (var executor = new com.mewcode.tool.ToolExecutor(registry,
                new ToolExecutionContext(tempDir, java.time.Duration.ofSeconds(2), new FileStateCache()))) {
            var coordinator = new AgentTurnCoordinator(client, registry, executor, conversation,
                    ToolApiProtocol.OPENAI);
            BlockingQueue<AgentEvent> events = coordinator.start("inspect");
            List<AgentEvent> seen = awaitCompletion(events);

            assertTrue(seen.stream().anyMatch(event -> event instanceof AgentEvent.ToolCompleted
                    && ((AgentEvent.ToolCompleted) event).toolUseId().equals("call-read")));
            assertTrue(seen.stream().anyMatch(event -> event instanceof AgentEvent.ToolCompleted
                    && ((AgentEvent.ToolCompleted) event).toolUseId().equals("call-search")));
            var started = seen.stream()
                    .filter(event -> event instanceof AgentEvent.ToolStarted)
                    .map(event -> (AgentEvent.ToolStarted) event)
                    .toList();
            assertEquals(Map.of("value", "one"), started.get(0).arguments());
            assertEquals(Map.of("value", "two"), started.get(1).arguments());
            assertEquals(2, client.calls.size());
            assertFalse(client.toolRequests.get(0).isEmpty());
            assertTrue(client.toolRequests.get(1).isEmpty());
            assertEquals(1, client.calls.get(0).getMessages().size());
            assertEquals(3, client.calls.get(1).getMessages().size());

            var assistant = client.calls.get(1).getMessages().get(1);
            assertEquals("assistant", assistant.role());
            assertEquals(3, assistant.content().size());
            assertInstanceOf(ToolUseBlock.class, assistant.content().get(1));
            assertInstanceOf(ToolUseBlock.class, assistant.content().get(2));

            var results = client.calls.get(1).getMessages().get(2);
            assertEquals("user", results.role());
            assertEquals(2, results.content().size());
            assertInstanceOf(ToolResultBlock.class, results.content().get(0));
            assertInstanceOf(ToolResultBlock.class, results.content().get(1));
        }
    }

    @Test
    void doesNotStartAThirdRequestWhenFinalResponseUsesAnotherTool() throws Exception {
        var first = queue(
                new StreamEvent.ToolCallComplete("call-1", "Echo", Map.of("value", "one")),
                new StreamEvent.StreamEnd("tool_use"));
        var second = queue(
                new StreamEvent.ToolCallComplete("call-2", "Echo", Map.of("value", "two")),
                new StreamEvent.StreamEnd("tool_use"));
        var client = new QueueClient(List.of(first, second));
        var registry = new ToolRegistry();
        registry.register(new EchoTool());
        var conversation = new ConversationManager();

        try (var executor = new com.mewcode.tool.ToolExecutor(registry,
                new ToolExecutionContext(tempDir, java.time.Duration.ofSeconds(2), new FileStateCache()))) {
            var coordinator = new AgentTurnCoordinator(client, registry, executor, conversation,
                    ToolApiProtocol.OPENAI);
            List<AgentEvent> events = awaitCompletion(coordinator.start("one round only"));

            assertEquals(2, client.calls.size());
            assertTrue(client.toolRequests.get(1).isEmpty());
            assertInstanceOf(AgentEvent.Error.class, events.getLast());
            assertTrue(((AgentEvent.Error) events.getLast()).message().contains("一次工具结果回灌"));
        }
    }

    @Test
    void parseFailureStillEmitsToolStartedWithEmptyArguments() throws Exception {
        var first = queue(
                new StreamEvent.ToolCallParseError("call-invalid", "Echo", "invalid JSON"),
                new StreamEvent.StreamEnd("tool_use"));
        var second = queue(new StreamEvent.TextDelta("Recovered."), new StreamEvent.StreamEnd("end_turn"));
        var client = new QueueClient(List.of(first, second));
        var registry = new ToolRegistry();
        registry.register(new EchoTool());
        var conversation = new ConversationManager();

        try (var executor = new com.mewcode.tool.ToolExecutor(registry,
                new ToolExecutionContext(tempDir, java.time.Duration.ofSeconds(2), new FileStateCache()))) {
            var coordinator = new AgentTurnCoordinator(client, registry, executor, conversation,
                    ToolApiProtocol.OPENAI);
            List<AgentEvent> events = awaitCompletion(coordinator.start("invalid"));

            var started = events.stream()
                    .filter(event -> event instanceof AgentEvent.ToolStarted)
                    .map(event -> (AgentEvent.ToolStarted) event)
                    .findFirst()
                    .orElseThrow();
            assertEquals("call-invalid", started.toolUseId());
            assertEquals("Echo", started.toolName());
            assertTrue(started.arguments().isEmpty());
        }
    }

    private static List<AgentEvent> awaitCompletion(BlockingQueue<AgentEvent> queue) throws Exception {
        var result = new ArrayList<AgentEvent>();
        while (true) {
            AgentEvent event = queue.poll(3, TimeUnit.SECONDS);
            assertNotNull(event, "agent turn timed out");
            result.add(event);
            if (event instanceof AgentEvent.Completed || event instanceof AgentEvent.Error) return result;
        }
    }

    private static BlockingQueue<StreamEvent> queue(StreamEvent... events) {
        var queue = new LinkedBlockingQueue<StreamEvent>();
        queue.addAll(List.of(events));
        return queue;
    }

    private static final class QueueClient implements LlmClient {
        private final List<BlockingQueue<StreamEvent>> responses;
        private final List<ConversationManager> calls = new ArrayList<>();
        private final List<List<Map<String, Object>>> toolRequests = new ArrayList<>();

        private QueueClient(List<BlockingQueue<StreamEvent>> responses) {
            this.responses = new ArrayList<>(responses);
        }

        @Override
        public synchronized BlockingQueue<StreamEvent> stream(ConversationManager conversation,
                                                               List<Map<String, Object>> tools) {
            var snapshot = new ConversationManager();
            for (var message : conversation.getMessages()) snapshot.addMessage(message);
            calls.add(snapshot);
            toolRequests.add(tools == null ? List.of() : List.copyOf(tools));
            return responses.removeFirst();
        }
    }

    private static final class EchoTool implements Tool {
        @Override
        public String name() { return "Echo"; }

        @Override
        public String description() { return "test echo"; }

        @Override
        public ToolCategory category() { return ToolCategory.SEARCH; }

        @Override
        public Map<String, Object> inputSchema() { return Map.of("type", "object"); }

        @Override
        public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
            return ToolResult.success("echo:" + input.get("value"));
        }

        @Override
        public boolean isReadOnly() { return true; }

        @Override
        public boolean isDestructive() { return false; }

        @Override
        public boolean isConcurrencySafe(Map<String, Object> input) { return true; }

        @Override
        public String validateInput(Map<String, Object> input) { return null; }
    }
}
