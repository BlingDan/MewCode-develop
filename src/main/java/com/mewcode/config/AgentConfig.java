package com.mewcode.config;

import com.mewcode.agent.AgentLoopConfig;

/** 与 Agent 相关的 YAML 配置。 */
public final class AgentConfig {

    private AgentLoopConfig loop = new AgentLoopConfig();

    public AgentLoopConfig getLoop() {
        return loop;
    }

    public void setLoop(AgentLoopConfig loop) {
        this.loop = loop == null ? new AgentLoopConfig() : loop;
    }
}
