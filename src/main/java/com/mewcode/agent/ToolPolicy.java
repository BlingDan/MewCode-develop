package com.mewcode.agent;

import com.mewcode.subagent.SubAgentSpec;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolRegistry;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 对模型请求和本地执行器共同使用的工具策略。
 *
 * <p>策略同时作用于发给 provider 的工具声明和真正执行前的校验，避免只靠提示词 约束 Plan Mode；Plan Mode 仅允许非破坏性的只读工具。
 */
public final class ToolPolicy {

  private final AgentMode mode;
  private final Set<String> allowedTools;
  private final boolean skillActive;
  private final boolean absolute;

  public static final Set<String> ALL_AGENT_DISALLOWED_TOOLS =
      Set.of(
          "Agent",
          "AskUserQuestion",
          "TaskList",
          "TaskGet",
          "TaskCreate",
          "TaskUpdate",
          "TaskStop");
  public static final Set<String> CUSTOM_AGENT_DISALLOWED_TOOLS = Set.of();
  public static final Set<String> ASYNC_AGENT_ALLOWED_TOOLS =
      Set.of(
          "ReadFile", "WriteFile", "EditFile", "Bash", "Glob", "Grep", "ToolSearch", "LoadSkill");

  private ToolPolicy(AgentMode mode, Set<String> allowedTools, boolean skillActive) {
    this(mode, allowedTools, skillActive, false);
  }

  private ToolPolicy(
      AgentMode mode, Set<String> allowedTools, boolean skillActive, boolean absolute) {
    this.mode = Objects.requireNonNull(mode, "mode");
    this.allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
    this.skillActive = skillActive;
    this.absolute = absolute;
  }

  /** 从本轮 Agent 模式创建不可变策略。 */
  public static ToolPolicy forMode(AgentMode mode) {
    return new ToolPolicy(mode, Set.of(), false);
  }

  /** 创建同时受 Skill 白名单约束的策略；skillActive 区分空白名单与未激活。 */
  public static ToolPolicy forModeAndTools(
      AgentMode mode, Set<String> allowedTools, boolean skillActive) {
    return new ToolPolicy(mode, allowedTools, skillActive);
  }

  /** 从父策略逐层收窄，生成子 Agent 同时用于 schema 和执行入口的策略。 */
  public static ToolPolicy forSubAgent(
      ToolRegistry registry, ToolPolicy parent, SubAgentSpec spec, boolean background) {
    return forSubAgent(registry, parent, spec, background, CUSTOM_AGENT_DISALLOWED_TOOLS);
  }

  /** 允许启动方注入项目/用户/插件级的额外禁止列表。 */
  public static ToolPolicy forSubAgent(
      ToolRegistry registry,
      ToolPolicy parent,
      SubAgentSpec spec,
      boolean background,
      Set<String> customDisallowedTools) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(parent, "parent");
    Objects.requireNonNull(spec, "spec");
    var allowed = new LinkedHashSet<String>();
    for (Tool tool : registry.getAll()) {
      if (!parent.isAllowed(tool)) continue;
      String name = tool.name();
      if (ALL_AGENT_DISALLOWED_TOOLS.contains(name)) continue;
      if (spec.source() != SubAgentSpec.Source.BUILTIN
          && customDisallowedTools != null
          && customDisallowedTools.contains(name)) continue;
      if (background && !ASYNC_AGENT_ALLOWED_TOOLS.contains(name)) continue;
      if (spec.disallowedTools().contains(name)) continue;
      if (spec.tools() != null && !spec.tools().contains(name)) continue;
      allowed.add(name);
    }
    return new ToolPolicy(parent.mode, allowed, false, true);
  }

  public AgentMode mode() {
    return mode;
  }

  /** 判断工具是否可被当前模式声明并执行。 */
  public boolean isAllowed(Tool tool) {
    Objects.requireNonNull(tool, "tool");
    if (absolute) return allowedTools.contains(tool.name());
    if (tool.isSystem()) return true;
    if (tool.isSkillTool() && !skillActive) return false;
    if (skillActive && !allowedTools.contains(tool.name())) return false;
    return mode == AgentMode.EXECUTE || tool.isReadOnly() && !tool.isDestructive();
  }
}
