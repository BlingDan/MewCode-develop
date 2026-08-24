package com.mewcode.agent;

import com.mewcode.tool.Tool;

import java.util.Objects;

/** 对模型请求和本地执行器共同使用的工具策略。 */
public final class ToolPolicy {

    private final AgentMode mode;

    private ToolPolicy(AgentMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public static ToolPolicy forMode(AgentMode mode) {
        return new ToolPolicy(mode);
    }

    public AgentMode mode() {
        return mode;
    }

    public boolean isAllowed(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        return mode == AgentMode.EXECUTE
                || tool.isReadOnly() && !tool.isDestructive();
    }
}
