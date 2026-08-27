package com.mewcode.config;

import java.util.ArrayList;
import java.util.List;

/** MewCode YAML 配置根对象，包含 provider、Agent Loop 和权限模式配置。 */
public final class AppConfig {

  private List<ProviderConfig> providers = new ArrayList<>();
  private AgentConfig agent = new AgentConfig();
  private PermissionConfig permissions = new PermissionConfig();

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
}
