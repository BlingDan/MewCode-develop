package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 固定 schema 的子 Agent 委派入口；实际分派由 AgentTurnCoordinator 完成。 */
public final class AgentTool implements Tool {

  public static final String NAME = "Agent";
  public static final List<String> MODEL_VALUES = List.of("inherit", "haiku", "sonnet", "opus");
  public static final Set<String> MODEL_NAMES = Set.copyOf(MODEL_VALUES);

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "将一个独立任务委派给子 Agent；指定 subagent_type 使用角色定义，省略时复用当前上下文后台执行。";
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
        Map.of(
            "prompt", Map.of("type", "string", "description", "要完成的子任务"),
            "description", Map.of("type", "string", "description", "任务的简短说明"),
            "subagent_type", Map.of("type", "string", "description", "可选角色名；省略时使用 Fork"),
            "model", Map.of("type", "string", "enum", MODEL_VALUES),
            "run_in_background", Map.of("type", "boolean", "default", false)),
        "required",
        List.of("prompt", "description"),
        "additionalProperties",
        false);
  }

  @Override
  public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
    return ToolResult.error("Agent 只能由 Agent 运行协调器执行。");
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
    return false;
  }

  @Override
  public boolean isSystem() {
    return true;
  }

  @Override
  public String validateInput(Map<String, Object> input) {
    if (input == null) return "Agent 参数不能为空。";
    if (!(input.get("prompt") instanceof String prompt) || prompt.isBlank()) {
      return "Agent 需要非空 prompt。";
    }
    if (!(input.get("description") instanceof String description) || description.isBlank()) {
      return "Agent 需要非空 description。";
    }
    Object type = input.get("subagent_type");
    if (type != null && (!(type instanceof String text) || text.isBlank())) {
      return "subagent_type 必须是非空字符串。";
    }
    Object model = input.get("model");
    if (model != null && (!(model instanceof String text) || !MODEL_NAMES.contains(text))) {
      return "model 必须是 inherit、haiku、sonnet 或 opus。";
    }
    Object background = input.get("run_in_background");
    return background != null && !(background instanceof Boolean)
        ? "run_in_background 必须是布尔值。"
        : null;
  }
}
