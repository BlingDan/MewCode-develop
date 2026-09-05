package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolResult;
import java.util.List;
import java.util.Map;

/** 按需加载 Skill 的系统工具声明；实际调度由 Agent 协调器处理。 */
public final class LoadSkillTool implements Tool {

  public static final String NAME = "LoadSkill";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "按名称加载一个可用 Skill 的完整指令和专属工具；需要复用工作流时调用。";
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
            "name", Map.of("type", "string", "description", "Skill 名称"),
            "arguments", Map.of("type", "string", "description", "传给 Skill 的原始参数")),
        "required",
        List.of("name"),
        "additionalProperties",
        false);
  }

  @Override
  public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
    return ToolResult.error("LoadSkill 只能由 Agent 运行协调器执行。");
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
    Object name = input == null ? null : input.get("name");
    if (!(name instanceof String text) || text.isBlank()) return "请传入要加载的 Skill 名称。";
    Object arguments = input.get("arguments");
    if (arguments != null && !(arguments instanceof String)) return "arguments 必须是字符串。";
    return null;
  }
}
