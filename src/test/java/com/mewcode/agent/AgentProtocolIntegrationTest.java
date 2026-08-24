package com.mewcode.agent;

import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.AnthropicClient;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.OpenAiClient;
import com.mewcode.llm.StreamEvent;
import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolExecutor;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用确定性 SSE 服务验证两个 provider adapter 共享同一套 Agent Loop。 */
class AgentProtocolIntegrationTest {

    @TempDir Path tempDir;

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void openAiAndDeepSeekOpenAiCompatibleEndpointsCompleteTheSameLoop() throws Exception {
        assertTwoRoundLoop("openai", ToolApiProtocol.OPENAI, false);
        assertTwoRoundLoop("deepseek", ToolApiProtocol.OPENAI, false);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void anthropicAndDeepSeekAnthropicCompatibleEndpointsCompleteTheSameLoop() throws Exception {
        assertTwoRoundLoop("anthropic", ToolApiProtocol.ANTHROPIC, true);
        assertTwoRoundLoop("deepseek-anthropic", ToolApiProtocol.ANTHROPIC, true);
    }

    private void assertTwoRoundLoop(String protocol,
                                    ToolApiProtocol apiProtocol,
                                    boolean anthropic) throws Exception {
        try (var service = new DeterministicService(anthropic)) {
            ProviderConfig provider = provider(service, protocol, anthropic);
            LlmClient client = anthropic
                    ? new AnthropicClient(provider, "system")
                    : new OpenAiClient(provider, "system");
            var registry = new ToolRegistry();
            registry.register(new EchoTool());
            var conversation = new ConversationManager();

            try (var executor = new ToolExecutor(registry,
                    new ToolExecutionContext(tempDir, Duration.ofSeconds(2), new FileStateCache()))) {
                var coordinator = new AgentTurnCoordinator(client, registry, executor,
                        conversation, apiProtocol, new AgentLoopConfig(5, 3));
                var events = collect(coordinator.startRun("inspect", AgentMode.EXECUTE));

                assertEquals(2, service.requests.get(),
                        () -> protocol + " requests=" + service.requests.get()
                                + " bodies=" + service.bodies);
                assertEquals(1, events.stream()
                        .filter(event -> event instanceof AgentEvent.ToolUse).count());
                assertEquals(1, events.stream()
                        .filter(event -> event instanceof AgentEvent.ToolResult).count());
                var usage = events.stream()
                        .filter(event -> event instanceof AgentEvent.Usage)
                        .map(event -> (AgentEvent.Usage) event)
                        .reduce((first, second) -> second)
                        .orElseThrow();
                assertEquals(12,
                        usage.inputTokens().orElseThrow());
                assertEquals(6, usage.outputTokens().orElseThrow());
                assertEquals(2, ((AgentEvent.LoopComplete) events.getLast()).totalRounds());
                assertTrue(service.bodies.getLast().contains("echo:one"),
                        service.bodies.getLast());
            }
        }
    }

    private static List<AgentEvent> collect(AgentRun run) throws Exception {
        var result = new ArrayList<AgentEvent>();
        while (true) {
            AgentEvent event = run.events().poll(5, TimeUnit.SECONDS);
            if (event == null) throw new AssertionError("Agent Loop did not complete");
            result.add(event);
            if (event instanceof AgentEvent.LoopComplete) return result;
        }
    }

    private static ProviderConfig provider(DeterministicService service,
                                           String protocol,
                                           boolean anthropic) {
        var provider = new ProviderConfig();
        provider.setName("test-" + protocol);
        provider.setProtocol(protocol);
        provider.setModel(protocol.equals("anthropic") ? "deepseek-reasoner" : "deepseek-chat");
        provider.setApiKey("integration-key");
        provider.setBaseUrl(service.baseUrl(anthropic));
        return provider;
    }

    private static final class DeterministicService implements AutoCloseable {
        private final HttpServer server;
        private final boolean anthropic;
        private final AtomicInteger requests = new AtomicInteger();
        private final List<String> bodies = new CopyOnWriteArrayList<>();

        private DeterministicService(boolean anthropic) throws IOException {
            this.anthropic = anthropic;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", this::handle);
            server.createContext("/v1/messages", this::handle);
            server.start();
        }

        private String baseUrl(boolean anthropicPath) {
            return "http://127.0.0.1:" + server.getAddress().getPort()
                    + (anthropicPath ? "" : "/v1");
        }

        private void handle(HttpExchange exchange) throws IOException {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int request = requests.incrementAndGet();
            String body = anthropic ? anthropicResponse(request) : openAiResponse(request);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        private static String openAiResponse(int request) {
            if (request == 1) {
                return """
                        data: {"id":"loop","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"Echo","arguments":"{\\"value\\":\\"one\\"}"}}]},"finish_reason":"tool_calls"}]}

                        data: {"id":"loop","object":"chat.completion.chunk","created":1,"model":"test","choices":[],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}

                        data: [DONE]

                        """;
            }
            return """
                    data: {"id":"loop","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{"content":"Done."},"finish_reason":"stop"}]}

                    data: {"id":"loop","object":"chat.completion.chunk","created":1,"model":"test","choices":[],"usage":{"prompt_tokens":7,"completion_tokens":4,"total_tokens":11}}

                    data: [DONE]

                    """;
        }

        private static String anthropicResponse(int request) {
            if (request == 1) {
                return """
                        event: message_start
                        data: {"type":"message_start","message":{"id":"msg-1","type":"message","role":"assistant","content":[],"model":"test","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":0}}}

                        event: content_block_start
                        data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool-1","name":"Echo","input":{}}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"value\\":\\"one\\"}"}}

                        event: content_block_stop
                        data: {"type":"content_block_stop","index":0}

                        event: message_delta
                        data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":2}}

                        event: message_stop
                        data: {"type":"message_stop"}

                        """;
            }
            return """
                    event: message_start
                    data: {"type":"message_start","message":{"id":"msg-2","type":"message","role":"assistant","content":[],"model":"test","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":7,"output_tokens":0}}}

                    event: content_block_start
                    data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Done."}}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":0}

                    event: message_delta
                    data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":4}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class EchoTool implements Tool {
        @Override public String name() { return "Echo"; }
        @Override public String description() { return "echo"; }
        @Override public ToolCategory category() { return ToolCategory.SEARCH; }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        @Override public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
            return ToolResult.success("echo:" + input.get("value"));
        }
        @Override public boolean isReadOnly() { return true; }
        @Override public boolean isDestructive() { return false; }
        @Override public boolean isConcurrencySafe(Map<String, Object> input) { return true; }
        @Override public String validateInput(Map<String, Object> input) { return null; }
    }
}
