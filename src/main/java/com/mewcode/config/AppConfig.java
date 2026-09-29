package com.mewcode.config;

import com.mewcode.agent.AgentLoopConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MewCode YAML 配置根对象，包含 provider、Agent Loop 和权限模式配置。 */
public final class AppConfig {

  private List<ProviderConfig> providers = new ArrayList<>();
  private AgentConfig agent = new AgentConfig();
  private PermissionConfig permissions = new PermissionConfig();
  private WorktreeConfig worktree = new WorktreeConfig();
  private Map<String, Object> mcpServers = new LinkedHashMap<>();

  /** 返回 provider 配置列表，供启动时选择和创建客户端。 */
  public List<ProviderConfig> getProviders() {
    return providers;
  }

  /** 设置 provider 列表；空值归一化为空列表，交给 ConfigLoader 报出明确错误。 */
  public void setProviders(List<ProviderConfig> providers) {
    this.providers = providers == null ? new ArrayList<>() : providers;
  }

  /** 返回 Agent 相关配置。 */
  public AgentConfig getAgent() {
    return agent;
  }

  /** 设置 Agent 配置；空值使用默认 Loop 配置。 */
  public void setAgent(AgentConfig agent) {
    this.agent = agent == null ? new AgentConfig() : agent;
  }

  /** 返回权限模式配置。 */
  public PermissionConfig getPermissions() {
    return permissions;
  }

  /** 设置权限模式配置；空值使用 default。 */
  public void setPermissions(PermissionConfig permissions) {
    this.permissions = permissions == null ? new PermissionConfig() : permissions;
  }

  /** 返回项目级 MCP Server 原始配置，由 MCP 专用加载器逐条校验。 */
  public Map<String, Object> getMcpServers() {
    return mcpServers;
  }

  public WorktreeConfig getWorktree() {
    return worktree;
  }

  public void setWorktree(WorktreeConfig value) {
    worktree = value == null ? new WorktreeConfig() : value;
  }

  /** 设置项目级 MCP Server 原始配置；空值按没有 MCP 配置处理。 */
  public void setMcpServers(Map<String, Object> mcpServers) {
    this.mcpServers = mcpServers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(mcpServers);
  }

  /** 与 Agent 相关的 YAML 配置。 */
  public static final class AgentConfig {
    private AgentLoopConfig loop = new AgentLoopConfig();
    private SubAgentConfig subagent = new SubAgentConfig();

    public AgentLoopConfig getLoop() {
      return loop;
    }

    public void setLoop(AgentLoopConfig loop) {
      this.loop = loop == null ? new AgentLoopConfig() : loop;
    }

    public SubAgentConfig getSubagent() {
      return subagent;
    }

    public void setSubagent(SubAgentConfig subagent) {
      this.subagent = subagent == null ? new SubAgentConfig() : subagent;
    }
  }

  /** SubAgent 的全局运行配置。 */
  public static final class SubAgentConfig {
    public static final long DEFAULT_AUTO_BACKGROUND_MS = 20_000L;

    private long autoBackgroundMs = DEFAULT_AUTO_BACKGROUND_MS;

    public long getAutoBackgroundMs() {
      return autoBackgroundMs;
    }

    public void setAutoBackgroundMs(long autoBackgroundMs) {
      this.autoBackgroundMs = autoBackgroundMs;
    }

    public void validate() {
      if (autoBackgroundMs <= 0) {
        throw new IllegalArgumentException("autoBackgroundMs must be positive");
      }
    }

    public SubAgentConfig copy() {
      var copy = new SubAgentConfig();
      copy.setAutoBackgroundMs(autoBackgroundMs);
      copy.validate();
      return copy;
    }
  }

  /** 权限模式配置，规则文件由 {@link PermissionConfigLoader} 分层加载。 */
  public static final class PermissionConfig {
    private String mode = "default";

    public String getMode() {
      return mode;
    }

    public void setMode(String mode) {
      this.mode = mode == null || mode.isBlank() ? "default" : mode;
    }
  }
}
