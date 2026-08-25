package com.mewcode.config;

import com.mewcode.agent.AgentLoopConfig;

/** 与 Agent 相关的 YAML 配置，当前包含 ReAct Loop 的边界参数。 */
public final class AgentConfig {

    private AgentLoopConfig loop = new AgentLoopConfig();

    /** 返回迭代上限和未知工具保护配置。 */
    public AgentLoopConfig getLoop() {
        return loop;
    }

    /** 设置 Loop 配置；空值恢复默认值。 */
    public void setLoop(AgentLoopConfig loop) {
        this.loop = loop == null ? new AgentLoopConfig() : loop;
    }
}
