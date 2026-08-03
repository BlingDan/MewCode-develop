package com.mewcode.llm;

import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiClientTest {

    @Test
    void streamsChatCompletionsThroughCustomBaseUrl() throws Exception {
        String fixture = resource("/sse/openai-chat.txt");
        var body = new AtomicReference<String>();
        HttpServer server = server(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "text/event-stream", fixture);
        });
        try {
            ProviderConfig provider = provider(server, "integration-secret");
            provider.setThinking(true);
            var history = new ConversationManager();
            history.addUserMessage("first");
            history.addAssistantMessage("answer");
            history.addUserMessage("second");

            List<StreamEvent> events = collect(new OpenAiClient(provider, "SYSTEM_MARKER").stream(history));

            assertEquals("Hello ", ((StreamEvent.TextDelta) events.get(0)).text());
            assertEquals("OpenAI", ((StreamEvent.TextDelta) events.get(1)).text());
            assertInstanceOf(StreamEvent.StreamEnd.class, events.get(2));
            assertTrue(body.get().contains("SYSTEM_MARKER"));
            assertTrue(body.get().contains("first"));
            assertTrue(body.get().contains("second"));
            assertFalse(body.get().contains("reasoning"));
            assertFalse(body.get().contains("thinking"));
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
                    {"error":{"message":"bad key","type":"invalid_request_error","code":"invalid_api_key"}}
                    """);
        });
        try {
            ProviderConfig provider = provider(server, "unique-openai-key");
            var history = new ConversationManager();
            history.addUserMessage("hello");

            List<StreamEvent> events = collect(new OpenAiClient(provider, "system").stream(history));

            assertEquals(1, events.size());
            assertInstanceOf(StreamEvent.Error.class, events.getFirst());
            assertFalse(((StreamEvent.Error) events.getFirst()).message().contains("unique-openai-key"));
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
                    {"error":{"message":"slow down","type":"rate_limit_error","code":"rate_limit"}}
                    """);
        });
        try {
            var history = new ConversationManager();
            history.addUserMessage("hello");
            List<StreamEvent> events = collect(new OpenAiClient(
                    provider(server, "rate-limit-key"), "system").stream(history));

            assertInstanceOf(StreamEvent.Error.class, events.getFirst());
            assertEquals(1, count.get());
        } finally {
            server.stop(0);
        }
    }

    private static ProviderConfig provider(HttpServer server, String key) {
        var provider = new ProviderConfig();
        provider.setName("test");
        provider.setProtocol("openai");
        provider.setModel("gpt-test");
        provider.setApiKey(key);
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
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
        server.createContext("/v1/chat/completions", exchange -> {
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
        try (var input = OpenAiClientTest.class.getResourceAsStream(name)) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
