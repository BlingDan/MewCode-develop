package com.mewcode.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MewCode YAML 配置根对象，包含 provider、Agent Loop 和权限模式配置。 */
public final class AppConfig {

  private List<ProviderConfig> providers = new ArrayList<>();
  private AgentConfig agent = new AgentConfig();
  private PermissionConfig permissions = new PermissionConfig();
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

  /** 设置项目级 MCP Server 原始配置；空值按没有 MCP 配置处理。 */
  public void setMcpServers(Map<String, Object> mcpServers) {
    this.mcpServers = mcpServers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(mcpServers);
  }
}
