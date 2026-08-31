package com.mewcode.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mewcode.config.McpServerConfig;
import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class McpHttpTransportIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void usesStreamableHttpSessionHeadersAndCallsTheTool() throws Exception {
    var requests = new CopyOnWriteArrayList<Request>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/mcp", exchange -> handle(exchange, requests));
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();

    var registry = new ToolRegistry();
    var manager = new McpManager(registry);
    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    var config =
        new McpServerConfig("remote", null, List.of(), Map.of(), url, Map.of("X-Test", "present"));
    try {
      assertEquals(List.of("remote"), manager.connectAll(List.of(config)).connectedServers());
      ToolResult result =
          registry
              .get("mcp_remote_http_echo")
              .orElseThrow()
              .execute(
                  new ToolExecutionContext(
                      Path.of(System.getProperty("user.dir")), new FileStateCache()),
                  Map.of("value", "ok"));
      assertFalse(result.isError(), result.content());
      assertEquals("http:ok", result.content());
    } finally {
      manager.shutdown();
      server.stop(0);
    }

    List<Request> posts =
        requests.stream().filter(request -> request.method.equals("POST")).toList();
    assertTrue(posts.stream().anyMatch(request -> request.methodName().equals("initialize")));
    assertTrue(
        posts.stream()
            .anyMatch(request -> request.methodName().equals("notifications/initialized")));
    assertTrue(posts.stream().anyMatch(request -> request.methodName().equals("tools/list")));
    assertTrue(posts.stream().anyMatch(request -> request.methodName().equals("tools/call")));
    assertTrue(
        posts.stream().allMatch(request -> "present".equals(request.headers.getFirst("X-Test"))));
    List<Request> sessionRequests =
        posts.stream().filter(request -> !request.methodName().equals("initialize")).toList();
    assertTrue(
        sessionRequests.stream()
            .allMatch(request -> "session-1".equals(request.headers.getFirst("Mcp-Session-Id"))));
    assertTrue(
        posts.stream()
            .allMatch(
                request ->
                    McpManager.SUPPORTED_PROTOCOL_VERSION.equals(
                        request.headers.getFirst("Mcp-Protocol-Version"))));
  }

  private static void handle(HttpExchange exchange, List<Request> requests) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Request request = new Request(exchange.getRequestMethod(), exchange.getRequestHeaders(), body);
    requests.add(request);
    if ("GET".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    if ("DELETE".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }

    JsonNode message = JSON.readTree(body);
    String method = message.path("method").asText();
    if ("notifications/initialized".equals(method)) {
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      return;
    }

    ObjectNode result = JSON.createObjectNode();
    String sessionId = null;
    boolean sseResponse = false;
    switch (method) {
      case "initialize" -> {
        result.put("protocolVersion", McpManager.SUPPORTED_PROTOCOL_VERSION);
        result.set("capabilities", JSON.createObjectNode().set("tools", JSON.createObjectNode()));
        result.set(
            "serverInfo", JSON.createObjectNode().put("name", "http-test").put("version", "1"));
        sessionId = "session-1";
      }
      case "tools/list" -> result.set("tools", tools());
      case "tools/call" -> {
        sseResponse = true;
        result.set(
            "content",
            JSON.createArrayNode()
                .add(
                    JSON.createObjectNode()
                        .put("type", "text")
                        .put(
                            "text",
                            "http:"
                                + message
                                    .path("params")
                                    .path("arguments")
                                    .path("value")
                                    .asText())));
      }
      default -> {
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
        return;
      }
    }

    ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
    response.set("id", message.get("id"));
    response.set("result", result);
    byte[] bytes = JSON.writeValueAsBytes(response);
    if (sessionId != null) exchange.getResponseHeaders().add("Mcp-Session-Id", sessionId);
    if (sseResponse) {
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
      byte[] event =
          ("event: message\ndata: " + new String(bytes, StandardCharsets.UTF_8) + "\n\n")
              .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, event.length);
      exchange.getResponseBody().write(event);
    } else {
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }

  private static ArrayNode tools() {
    ObjectNode schema = JSON.createObjectNode().put("type", "object");
    schema.set(
        "properties",
        JSON.createObjectNode().set("value", JSON.createObjectNode().put("type", "string")));
    return JSON.createArrayNode()
        .add(
            JSON.createObjectNode()
                .put("name", "http_echo")
                .put("description", "http echo")
                .set("inputSchema", schema));
  }

  private record Request(String method, com.sun.net.httpserver.Headers headers, String body) {
    private String methodName() {
      try {
        return JSON.readTree(body).path("method").asText();
      } catch (IOException error) {
        return "";
      }
    }
  }
}
