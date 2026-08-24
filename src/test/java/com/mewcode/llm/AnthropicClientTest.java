package com.mewcode.llm;

import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.prompt.PromptBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicClientTest {

    @Test
    void streamsThinkingTextAndCompleteInOrder() throws Exception {
        String fixture = resource("/sse/anthropic-thinking.txt");
        var body = new AtomicReference<String>();
        HttpServer server = server(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "text/event-stream", fixture);
        });
        try {
            ProviderConfig provider = provider(server, "integration-secret", true);
            String projectRoot = Path.of("/tmp/mewcode-anthropic-project")
                    .toAbsolutePath().normalize().toString();
            var history = new ConversationManager();
            history.addUserMessage("first");
            history.addAssistantMessage("answer");
            history.addUserMessage("second");

            List<StreamEvent> events = collect(new AnthropicClient(
                    provider, PromptBuilder.buildSystemPrompt(Path.of(projectRoot))).stream(history));

            assertInstanceOf(StreamEvent.ThinkingDelta.class, events.get(0));
            assertEquals("HIDDEN_THOUGHT", ((StreamEvent.ThinkingDelta) events.get(0)).text());
            assertEquals("Hello ", ((StreamEvent.TextDelta) events.get(1)).text());
            assertEquals("Claude", ((StreamEvent.TextDelta) events.get(2)).text());
            assertInstanceOf(StreamEvent.StreamEnd.class, events.get(3));
            assertTrue(body.get().contains(projectRoot));
            assertTrue(body.get().contains("\"thinking\""));
            assertTrue(body.get().contains("first"));
            assertTrue(body.get().contains("second"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamsToolUseAndSendsAnthropicToolDefinitions() throws Exception {
        String fixture = resource("/sse/anthropic-tool-use.txt");
        var body = new AtomicReference<String>();
        HttpServer server = server(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "text/event-stream", fixture);
        });
        try {
            var history = new ConversationManager();
            history.addUserMessage("read the file");
            List<StreamEvent> events = collect(new AnthropicClient(
                    provider(server, "tool-key", false), "system").stream(history,
                    List.of(Map.of("name", "ReadFile",
                            "description", "read a file",
                            "input_schema", Map.of("type", "object")))));

            assertInstanceOf(StreamEvent.ToolCallComplete.class, events.get(0));
            var call = (StreamEvent.ToolCallComplete) events.get(0);
            assertEquals("toolu_1", call.toolUseId());
            assertEquals("ReadFile", call.toolName());
            assertEquals("/tmp/main.py", call.arguments().get("path"));
            assertEquals("tool_use", ((StreamEvent.StreamEnd) events.get(1)).stopReason());
            assertTrue(body.get().contains("\"tools\""), body.get());
            assertTrue(body.get().contains("ReadFile"), body.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void authenticationErrorIsSafeAndNotRetried() throws Exception {
        var count = new AtomicInteger();
        HttpServer server = server(exchange -> {
            count.incrementAndGet();
            respond(exchange, 401, "application/json", """
                    {"type":"error","error":{"type":"authentication_error","message":"bad key"}}
                    """);
        });
        try {
            ProviderConfig provider = provider(server, "unique-anthropic-key", false);
            var history = new ConversationManager();
            history.addUserMessage("hello");

            List<StreamEvent> events = collect(new AnthropicClient(provider, "system").stream(history));

            assertEquals(1, events.size());
            assertInstanceOf(StreamEvent.Error.class, events.getFirst());
            assertFalse(((StreamEvent.Error) events.getFirst()).message().contains("unique-anthropic-key"));
            assertEquals(1, count.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rateLimitIsNotRetried() throws Exception {
        var count = new AtomicInteger();
        HttpServer server = server(exchange -> {
            count.incrementAndGet();
            respond(exchange, 429, "application/json", """
                    {"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}
                    """);
        });
        try {
            var history = new ConversationManager();
            history.addUserMessage("hello");
            List<StreamEvent> events = collect(new AnthropicClient(
                    provider(server, "rate-limit-key", false), "system").stream(history));

            assertInstanceOf(StreamEvent.Error.class, events.getFirst());
            assertEquals(1, count.get());
        } finally {
            server.stop(0);
        }
    }

    private static ProviderConfig provider(HttpServer server, String key, boolean thinking) {
        var provider = new ProviderConfig();
        provider.setName("test");
        provider.setProtocol("anthropic");
        provider.setModel("claude-test");
        provider.setApiKey(key);
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        provider.setThinking(thinking);
        return provider;
    }

    private static List<StreamEvent> collect(java.util.concurrent.BlockingQueue<StreamEvent> queue)
            throws Exception {
        var events = new ArrayList<StreamEvent>();
        while (true) {
            StreamEvent event = queue.poll(5, TimeUnit.SECONDS);
            assertNotNull(event, "stream timed out");
            events.add(event);
            if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) return events;
        }
    }

    private static HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            try { handler.handle(exchange); } finally { exchange.close(); }
        });
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String content)
            throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String resource(String name) throws IOException {
        try (var input = AnthropicClientTest.class.getResourceAsStream(name)) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
