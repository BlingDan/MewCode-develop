package com.mewcode.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.TextBlock;
import com.mewcode.conversation.ThinkingBlock;
import com.mewcode.prompt.PromptBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAiClientTest {

  @Test
  void streamsChatCompletionsThroughCustomBaseUrl() throws Exception {
    String fixture = resource("/sse/openai-chat.txt");
    var body = new AtomicReference<String>();
    HttpServer server =
        server(
            exchange -> {
              body.set(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
              respond(exchange, 200, "text/event-stream", fixture);
            });
    try {
      ProviderConfig provider = provider(server, "integration-secret");
      provider.setThinking(true);
      String projectRoot =
          Path.of("/tmp/mewcode-openai-project").toAbsolutePath().normalize().toString();
      var history = new ConversationManager();
      history.addUserMessage("first");
      history.addAssistantMessage("answer");
      history.addUserMessage("second");

      List<StreamEvent> events =
          collect(
              new OpenAiClient(provider, PromptBuilder.buildSystemPrompt(Path.of(projectRoot)))
                  .stream(history));

      assertEquals("Hello ", ((StreamEvent.TextDelta) events.get(0)).text());
      assertEquals("OpenAI", ((StreamEvent.TextDelta) events.get(1)).text());
      var usage =
          events.stream()
              .filter(event -> event instanceof StreamEvent.Usage)
              .map(event -> (StreamEvent.Usage) event)
              .findFirst()
              .orElseThrow();
      assertEquals(11, usage.inputTokens().orElseThrow());
      assertEquals(3, usage.outputTokens().orElseThrow());
      assertInstanceOf(StreamEvent.StreamEnd.class, events.getLast());
      assertTrue(body.get().contains(projectRoot));
      assertTrue(body.get().contains("first"));
      assertTrue(body.get().contains("second"));
      assertFalse(body.get().contains("reasoning"));
      assertFalse(body.get().contains("thinking"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void deepSeekUsesTheSameRootAwareOpenAiRequest() throws Exception {
    String fixture = resource("/sse/openai-chat.txt");
    var body = new AtomicReference<String>();
    HttpServer server =
        server(
            exchange -> {
              body.set(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
              respond(exchange, 200, "text/event-stream", fixture);
            });
    try {
      ProviderConfig provider = provider(server, "deepseek-key");
      provider.setProtocol("deepseek");
      String projectRoot =
          Path.of("/tmp/mewcode-deepseek-project").toAbsolutePath().normalize().toString();
      var history = new ConversationManager();
      history.addUserMessage("hello");

      collect(
          new OpenAiClient(provider, PromptBuilder.buildSystemPrompt(Path.of(projectRoot)))
              .stream(history));

      assertTrue(body.get().contains(projectRoot), body.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void preservesDeepSeekReasoningContentForTheNextRequest() throws Exception {
    String fixture =
        """
                data: {"id":"chatcmpl-reasoning","object":"chat.completion.chunk","created":1710000000,"model":"deepseek-chat","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"inspect first"},"finish_reason":null}]}

                data: {"id":"chatcmpl-reasoning","object":"chat.completion.chunk","created":1710000000,"model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"Done"},"finish_reason":null}]}

                data: {"id":"chatcmpl-reasoning","object":"chat.completion.chunk","created":1710000000,"model":"deepseek-chat","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]
                """;
    var bodies = new ArrayList<String>();
    HttpServer server =
        server(
            exchange -> {
              bodies.add(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
              respond(exchange, 200, "text/event-stream", fixture);
            });
    try {
      ProviderConfig provider = provider(server, "deepseek-key");
      provider.setProtocol("deepseek");
      var client = new OpenAiClient(provider, "system");
      var first = new ConversationManager();
      first.addUserMessage("inspect");
      List<StreamEvent> events = collect(client.stream(first));

      var reasoning =
          events.stream()
              .filter(event -> event instanceof StreamEvent.ThinkingDelta)
              .map(event -> ((StreamEvent.ThinkingDelta) event).text())
              .findFirst()
              .orElseThrow();
      assertEquals("inspect first", reasoning);

      var second = new ConversationManager();
      second.addUserMessage("inspect");
      second.addAssistantMessage(List.of(new ThinkingBlock(reasoning, ""), new TextBlock("Done")));
      second.addUserMessage("continue");
      collect(client.stream(second));

      assertEquals(2, bodies.size());
      assertTrue(bodies.getLast().contains("reasoning_content"), bodies.getLast());
      assertTrue(bodies.getLast().contains("inspect first"), bodies.getLast());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void streamsToolUseAndSendsProviderToolDefinitions() throws Exception {
    String fixture = resource("/sse/openai-tool-use.txt");
    var body = new AtomicReference<String>();
    HttpServer server =
        server(
            exchange -> {
              body.set(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
              respond(exchange, 200, "text/event-stream", fixture);
            });
    try {
      var history = new ConversationManager();
      history.addUserMessage("read the file");
      List<StreamEvent> events =
          collect(
              new OpenAiClient(provider(server, "tool-key"), "system")
                  .stream(
                      history,
                      List.of(
                          Map.of(
                              "type",
                              "function",
                              "function",
                              Map.of(
                                  "name", "ReadFile",
                                  "description", "read a file",
                                  "parameters", Map.of("type", "object"))))));

      assertInstanceOf(StreamEvent.ToolCallComplete.class, events.get(0));
      var call = (StreamEvent.ToolCallComplete) events.get(0);
      assertEquals("call_1", call.toolUseId());
      assertEquals("ReadFile", call.toolName());
      assertEquals("/tmp/main.py", call.arguments().get("path"));
      assertInstanceOf(StreamEvent.StreamEnd.class, events.get(1));
      assertTrue(body.get().contains("\"tools\""), body.get());
      assertTrue(body.get().contains("ReadFile"), body.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void serializesStructuredSystemSegmentsToolsHistoryAndReminderSeparately() throws Exception {
    String fixture = resource("/sse/openai-chat.txt");
    var body = new AtomicReference<String>();
    HttpServer server =
        server(
            exchange -> {
              body.set(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
              respond(exchange, 200, "text/event-stream", fixture);
            });
    try {
      var history =
          List.of(
              new com.mewcode.conversation.Message("user", "original"),
              new com.mewcode.conversation.Message("assistant", "previous"));
      var reminder =
          new com.mewcode.conversation.Message(
              "user", "<system-reminder>\nround 1\n</system-reminder>");
      var request =
          new PromptRequest(
              List.of("stable system", "stable environment"),
              List.of(
                  Map.of(
                      "type",
                      "function",
                      "function",
                      Map.of(
                          "name",
                          "ReadFile",
                          "description",
                          "read a file",
                          "parameters",
                          Map.of("type", "object")))),
              history,
              Optional.of(reminder));

      collect(
          new OpenAiClient(provider(server, "structured-key"), "legacy")
              .openStream(request)
              .events());

      assertTrue(body.get().contains("stable system"), body.get());
      assertTrue(body.get().contains("stable environment"), body.get());
      assertTrue(body.get().contains("ReadFile"), body.get());
      assertTrue(body.get().contains("original"), body.get());
      assertTrue(body.get().contains("<system-reminder>"), body.get());
      assertEquals(2, request.history().size());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void authenticationErrorIsSafeAndNotRetried() throws Exception {
    var count = new AtomicInteger();
    HttpServer server =
        server(
            exchange -> {
              count.incrementAndGet();
              respond(
                  exchange,
                  401,
                  "application/json",
                  """
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
    HttpServer server =
        server(
            exchange -> {
              count.incrementAndGet();
              respond(
                  exchange,
                  429,
                  "application/json",
                  """
                    {"error":{"message":"slow down","type":"rate_limit_error","code":"rate_limit"}}
                    """);
            });
    try {
      var history = new ConversationManager();
      history.addUserMessage("hello");
      List<StreamEvent> events =
          collect(new OpenAiClient(provider(server, "rate-limit-key"), "system").stream(history));

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
      if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error)
        return events;
    }
  }

  private static HttpServer server(ExchangeHandler handler) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          try {
            handler.handle(exchange);
          } finally {
            exchange.close();
          }
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
