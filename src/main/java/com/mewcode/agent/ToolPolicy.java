package com.mewcode.agent;

import com.mewcode.tool.Tool;

import java.util.Objects;
import java.util.Set;

/**
 * 对模型请求和本地执行器共同使用的工具策略。
 *
 * <p>策略同时作用于发给 provider 的工具声明和真正执行前的校验，避免只靠提示词
 * 约束 Plan Mode；Plan Mode 仅允许非破坏性的只读工具。</p>
 */
public final class ToolPolicy {

    private final AgentMode mode;
    private final Set<String> allowedTools;
    private final boolean skillActive;

    private ToolPolicy(AgentMode mode, Set<String> allowedTools, boolean skillActive) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        this.skillActive = skillActive;
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

    public AgentMode mode() {
        return mode;
    }

    /** 判断工具是否可被当前模式声明并执行。 */
    public boolean isAllowed(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        if (tool.isSystem()) return true;
        if (tool.isSkillTool() && !skillActive) return false;
        if (skillActive && !allowedTools.contains(tool.name())) return false;
        return mode == AgentMode.EXECUTE || tool.isReadOnly() && !tool.isDestructive();
    }
}
