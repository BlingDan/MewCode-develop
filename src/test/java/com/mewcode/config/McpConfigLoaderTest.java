package com.mewcode.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigLoaderTest {

  @TempDir Path tempDir;

  @Test
  void mergesUserAndProjectServersWithProjectEntryReplacement() throws Exception {
    Path user = tempDir.resolve("user.yaml");
    Files.writeString(
        user,
        """
        mcp_servers:
          local:
            command: user-server
          same:
            command: old-server
            args: [old]
        """);

    var project = new LinkedHashMap<String, Object>();
    project.put("same", Map.of("url", "https://example.com/mcp"));
    project.put("remote", Map.of("url", "http://localhost:8080/mcp"));

    McpConfigLoader.Loaded loaded = McpConfigLoader.load(user, project);

    assertEquals(List.of("local", "same", "remote"), loaded.servers().stream().map(McpServerConfig::serverName).toList());
    assertTrue(loaded.servers().stream().filter(server -> server.serverName().equals("same")).findFirst().orElseThrow().isHttp());
    assertTrue(loaded.errors().isEmpty(), loaded.errors().toString());
  }

  @Test
  void expandsOnlyEnvironmentAndHeaderValues() throws Exception {
    Path user = tempDir.resolve("user.yaml");
    Files.writeString(
        user,
        """
        mcp_servers:
          stdio:
            command: ${NOT_EXPANDED}
            args: ["${NOT_EXPANDED}"]
            env:
              PATH_COPY: "prefix-${PATH}"
          http:
            url: https://example.com/mcp
            headers:
              X-Path: "${PATH}"
        """);

    McpConfigLoader.Loaded loaded = McpConfigLoader.load(user, Map.of());

    assertEquals(2, loaded.servers().size(), loaded.errors().toString());
    McpServerConfig stdio = loaded.servers().stream().filter(McpServerConfig::isStdio).findFirst().orElseThrow();
    assertEquals("${NOT_EXPANDED}", stdio.command());
    assertEquals("${NOT_EXPANDED}", stdio.args().getFirst());
    assertEquals("prefix-" + System.getenv("PATH"), stdio.env().get("PATH_COPY"));
    assertEquals(System.getenv("PATH"), loaded.servers().stream().filter(McpServerConfig::isHttp).findFirst().orElseThrow().headers().get("X-Path"));
  }

  @Test
  void invalidServerDoesNotBlockAnotherAndDoesNotLeakValues() throws Exception {
    Path user = tempDir.resolve("user.yaml");
    Files.writeString(
        user,
        """
        mcp_servers:
          broken:
            command: broken
            url: https://example.com/mcp
            token: ultra-secret
          good:
            url: https://example.com/mcp
        """);

    McpConfigLoader.Loaded loaded = McpConfigLoader.load(user, Map.of());

    assertEquals(List.of("good"), loaded.servers().stream().map(McpServerConfig::serverName).toList());
    assertFalse(loaded.errors().isEmpty());
    assertTrue(loaded.errors().stream().allMatch(error -> !error.contains("ultra-secret")));
    assertTrue(loaded.errors().stream().anyMatch(error -> error.contains("broken")));
  }

  @Test
  void undefinedVariableOnlyInvalidatesCurrentServer() throws Exception {
    Path user = tempDir.resolve("user.yaml");
    Files.writeString(
        user,
        """
        mcp_servers:
          broken:
            url: https://example.com/mcp
            headers:
              Authorization: "Bearer ${MEWCODE_VARIABLE_THAT_DOES_NOT_EXIST}"
          good:
            command: good-server
        """);

    McpConfigLoader.Loaded loaded = McpConfigLoader.load(user, Map.of());

    assertEquals(List.of("good"), loaded.servers().stream().map(McpServerConfig::serverName).toList());
    assertTrue(loaded.errors().getFirst().contains("headers"));
  }
}
