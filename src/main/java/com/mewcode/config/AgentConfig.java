package com.mewcode.config;

import com.mewcode.agent.AgentLoopConfig;

/** 与 Agent 相关的 YAML 配置，当前包含 ReAct Loop 的边界参数。 */
public final class AgentConfig {

  private AgentLoopConfig loop = new AgentLoopConfig();
  private SubAgentConfig subagent = new SubAgentConfig();

  /** 返回迭代上限和未知工具保护配置。 */
  public AgentLoopConfig getLoop() {
    return loop;
  }

  /** 设置 Loop 配置；空值恢复默认值。 */
  public void setLoop(AgentLoopConfig loop) {
    this.loop = loop == null ? new AgentLoopConfig() : loop;
  }

  /** 返回 SubAgent 的后台化配置。 */
  public SubAgentConfig getSubagent() {
    return subagent;
  }

  /** 设置 SubAgent 配置；空值恢复默认值。 */
  public void setSubagent(SubAgentConfig subagent) {
    this.subagent = subagent == null ? new SubAgentConfig() : subagent;
  }
}
