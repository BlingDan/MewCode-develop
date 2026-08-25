package com.mewcode.agent;

import com.mewcode.tool.Tool;

import java.util.Objects;

/**
 * 对模型请求和本地执行器共同使用的工具策略。
 *
 * <p>策略同时作用于发给 provider 的工具声明和真正执行前的校验，避免只靠提示词
 * 约束 Plan Mode；Plan Mode 仅允许非破坏性的只读工具。</p>
 */
public final class ToolPolicy {

    private final AgentMode mode;

    private ToolPolicy(AgentMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    /** 从本轮 Agent 模式创建不可变策略。 */
    public static ToolPolicy forMode(AgentMode mode) {
        return new ToolPolicy(mode);
    }

    public AgentMode mode() {
        return mode;
    }

    /** 判断工具是否可被当前模式声明并执行。 */
    public boolean isAllowed(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        return mode == AgentMode.EXECUTE
                || tool.isReadOnly() && !tool.isDestructive();
    }
}
