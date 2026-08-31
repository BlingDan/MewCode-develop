package com.mewcode.tool.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolSearchToolTest {

  @TempDir Path tempDir;

  @Test
  void discoversExactDeferredToolAndMakesItVisibleFromNextSnapshot() {
    var registry = new ToolRegistry();
    registry.register(new DeferredTool("mcp_demo_read"));
    registry.register(new ToolSearchTool(registry));

    assertTrue(registry.deferredToolNames().contains("mcp_demo_read"));
    assertFalse(
        registry.getModelVisibleTools().stream()
            .anyMatch(tool -> tool.name().equals("mcp_demo_read")));
    assertTrue(
        registry.getModelVisibleTools().stream()
            .anyMatch(tool -> tool.name().equals("ToolSearch")));

    ToolResult result =
        registry
            .get("ToolSearch")
            .orElseThrow()
            .execute(
                new ToolExecutionContext(tempDir, new FileStateCache()),
                Map.of("tool_name", "mcp_demo_read"));

    assertFalse(result.isError(), result.content());
    assertTrue(result.content().contains("mcp_demo_read"));
    assertTrue(registry.isDiscovered("mcp_demo_read"));
    assertTrue(
        registry.getModelVisibleTools().stream()
            .anyMatch(tool -> tool.name().equals("mcp_demo_read")));
    assertFalse(
        registry.getModelVisibleTools().stream()
            .anyMatch(tool -> tool.name().equals("ToolSearch")));
  }

  @Test
  void failedSearchDoesNotChangeState() {
    var registry = new ToolRegistry();
    registry.register(new DeferredTool("mcp_demo_read"));
    registry.register(new ToolSearchTool(registry));

    ToolResult result =
        registry
            .get("ToolSearch")
            .orElseThrow()
            .execute(
                new ToolExecutionContext(tempDir, new FileStateCache()),
                Map.of("tool_name", "missing"));

    assertTrue(result.isError());
    assertFalse(registry.isDiscovered("missing"));
    assertTrue(registry.deferredToolNames().contains("mcp_demo_read"));
  }

  private record DeferredTool(String name) implements Tool {
    @Override
    public String description() {
      return "deferred test tool";
    }

    @Override
    public ToolCategory category() {
      return ToolCategory.MCP;
    }

    @Override
    public Map<String, Object> inputSchema() {
      return Map.of("type", "object");
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
      return ToolResult.success("ok");
    }

    @Override
    public boolean isReadOnly() {
      return false;
    }

    @Override
    public boolean isDestructive() {
      return false;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
      return false;
    }

    @Override
    public String validateInput(Map<String, Object> input) {
      return null;
    }

    @Override
    public boolean shouldDefer() {
      return true;
    }
  }
}
