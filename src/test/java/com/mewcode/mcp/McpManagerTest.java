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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpManagerTest {

  @TempDir Path tempDir;

  @Test
  void completesOneHandshakeDiscoversPagesAndReusesSessionForCalls() throws Exception {
    Path log = tempDir.resolve("stdio.log");
    McpServerConfig config = stdioConfig("demo", log, McpManager.SUPPORTED_PROTOCOL_VERSION);
    ToolRegistry registry = new ToolRegistry();
    McpManager manager = new McpManager(registry);

    try {
      McpManager.ConnectionReport report = manager.connectAll(List.of(config));

      assertEquals(List.of("demo"), report.connectedServers());
      assertTrue(report.errors().isEmpty(), report.errors().toString());
      assertEquals(List.of("mcp_demo_alpha", "mcp_demo_beta"), manager.registeredSources().get("demo"));
      assertTrue(registry.get("ToolSearch").isPresent());

      ToolResult first = call(registry, "mcp_demo_alpha", Map.of("value", "one"));
      ToolResult second = call(registry, "mcp_demo_beta", Map.of("value", "two"));
      assertEquals("alpha:one", first.content());
      assertEquals("beta:two", second.content());
      assertFalse(first.isError());
      assertFalse(second.isError());

      List<String> messages = Files.readAllLines(log);
      assertEquals(
          List.of("initialize", "notifications/initialized", "tools/list", "tools/list:page2", "tools/call:alpha", "tools/call:beta"),
          messages);
    } finally {
      manager.shutdown();
    }

    assertTrue(Files.readAllLines(log).contains("closed"));
  }

  @Test
  void rejectsIncompatibleServerWithoutAffectingAnotherServer() throws Exception {
    Path badLog = tempDir.resolve("bad.log");
    Path goodLog = tempDir.resolve("good.log");
    McpServerConfig bad = stdioConfig("bad", badLog, "2025-03-26");
    McpServerConfig good = stdioConfig("good", goodLog, McpManager.SUPPORTED_PROTOCOL_VERSION);
    ToolRegistry registry = new ToolRegistry();
    McpManager manager = new McpManager(registry);

    try {
      McpManager.ConnectionReport report = manager.connectAll(List.of(bad, good));

      assertEquals(List.of("good"), report.connectedServers());
      assertEquals(1, report.errors().size());
      assertTrue(report.errors().getFirst().contains("bad"));
      assertTrue(report.errors().getFirst().contains("2025-03-26"));
      assertTrue(registry.get("mcp_good_alpha").isPresent());
      assertFalse(registry.get("mcp_bad_alpha").isPresent());
    } finally {
      manager.shutdown();
    }
  }

  private static ToolResult call(ToolRegistry registry, String name, Map<String, Object> input) {
    return registry
        .get(name)
        .orElseThrow()
        .execute(
            new ToolExecutionContext(Path.of(System.getProperty("user.dir")), new FileStateCache()),
            input);
  }

  private static McpServerConfig stdioConfig(String name, Path log, String version) {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    return new McpServerConfig(
        name,
        java,
        List.of(
            "-cp",
            System.getProperty("java.class.path"),
            StdioServer.class.getName(),
            log.toString(),
            version),
        Map.of(),
        null,
        Map.of());
  }

  /** 测试用最小 stdio MCP Server，只输出 JSON-RPC 到 stdout。 */
  public static final class StdioServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
      Path log = Path.of(args[0]);
      String version = args[1];
      try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) continue;
          JsonNode request = JSON.readTree(line);
          String method = request.path("method").asText();
          switch (method) {
            case "initialize" -> {
              append(log, "initialize");
              ObjectNode result = JSON.createObjectNode();
              result.put("protocolVersion", version);
              result.set("capabilities", JSON.createObjectNode().set("tools", JSON.createObjectNode()));
              result.set("serverInfo", JSON.createObjectNode().put("name", "test-server").put("version", "1"));
              respond(request, result);
            }
            case "notifications/initialized" -> append(log, "notifications/initialized");
            case "tools/list" -> {
              String cursor = request.path("params").path("cursor").asText("");
              if (cursor.isBlank()) {
                append(log, "tools/list");
                ObjectNode result = JSON.createObjectNode();
                result.set("tools", tools("alpha"));
                result.put("nextCursor", "page2");
                respond(request, result);
              } else {
                append(log, "tools/list:page2");
                respond(request, JSON.createObjectNode().set("tools", tools("beta")));
              }
            }
            case "tools/call" -> {
              String name = request.path("params").path("name").asText();
              String value = request.path("params").path("arguments").path("value").asText();
              append(log, "tools/call:" + name);
              ObjectNode result = JSON.createObjectNode();
              ArrayNode content = JSON.createArrayNode();
              content.add(JSON.createObjectNode().put("type", "text").put("text", name + ":" + value));
              result.set("content", content);
              respond(request, result);
            }
            default -> {
              if (request.has("id")) {
                ObjectNode error = JSON.createObjectNode().put("code", -32601).put("message", "not found");
                respondError(request, error);
              }
            }
          }
        }
      } finally {
        append(log, "closed");
      }
    }

    private static ArrayNode tools(String name) {
      ArrayNode tools = JSON.createArrayNode();
      ObjectNode schema = JSON.createObjectNode().put("type", "object");
      ObjectNode properties = JSON.createObjectNode();
      properties.set("value", JSON.createObjectNode().put("type", "string"));
      schema.set("properties", properties);
      schema.set("required", JSON.createArrayNode().add("value"));
      tools.add(
          JSON.createObjectNode()
              .put("name", name)
              .put("description", name + " tool")
              .set("inputSchema", schema));
      return tools;
    }

    private static void respond(JsonNode request, JsonNode result) throws Exception {
      ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
      response.set("id", request.get("id"));
      response.set("result", result);
      System.out.println(JSON.writeValueAsString(response));
      System.out.flush();
    }

    private static void respondError(JsonNode request, JsonNode error) throws Exception {
      ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
      response.set("id", request.get("id"));
      response.set("error", error);
      System.out.println(JSON.writeValueAsString(response));
      System.out.flush();
    }

    private static synchronized void append(Path path, String value) throws Exception {
      Files.writeString(
          path,
          value + System.lineSeparator(),
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
    }
  }
}
