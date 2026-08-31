package com.mewcode.tool.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.Map;

/** 在本地 Registry 中精确查找并发现延迟 MCP 工具。 */
public final class ToolSearchTool implements Tool {

  public static final String NAME = "ToolSearch";
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ToolRegistry registry;

  public ToolSearchTool(ToolRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "按完整工具名从本地 Registry 查找一个延迟 MCP 工具，并返回它的完整定义。只能精确匹配，不访问远端 Server。";
  }

  @Override
  public ToolCategory category() {
    return ToolCategory.SEARCH;
  }

  @Override
  public Map<String, Object> inputSchema() {
    return Map.of(
        "type",
        "object",
        "properties",
        Map.of("tool_name", Map.of("type", "string", "description", "要查找的完整注册工具名")),
        "required",
        java.util.List.of("tool_name"),
        "additionalProperties",
        false);
  }

  @Override
  public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
    String name = input == null ? null : stringValue(input.get("tool_name"));
    if (name == null || name.isBlank()) {
      return ToolResult.error("ToolSearch 需要非空的完整工具名 tool_name。");
    }
    return registry
        .findAndDiscover(name)
        .map(this::definition)
        .orElseGet(() -> ToolResult.error("未找到尚未发现的 MCP 工具：" + name));
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public boolean isDestructive() {
    return false;
  }

  @Override
  public boolean isConcurrencySafe(Map<String, Object> input) {
    return true;
  }

  @Override
  public String validateInput(Map<String, Object> input) {
    return input == null || stringValue(input.get("tool_name")) == null
        ? "ToolSearch 需要字符串参数 tool_name。"
        : null;
  }

  private ToolResult definition(Tool tool) {
    var definition = new LinkedHashMap<String, Object>();
    definition.put("name", tool.name());
    definition.put("description", tool.description());
    definition.put("input_schema", tool.inputSchema());
    if (tool.outputSchema() != null && !tool.outputSchema().isEmpty()) {
      definition.put("output_schema", tool.outputSchema());
    }
    try {
      return ToolResult.success(JSON.writeValueAsString(definition));
    } catch (JsonProcessingException error) {
      return ToolResult.error("ToolSearch 无法序列化工具定义：" + tool.name());
    }
  }

  private static String stringValue(Object value) {
    return value instanceof String text ? text : null;
  }
}
