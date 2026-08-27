package com.mewcode.config;

/** 权限模式配置，规则文件由 {@link PermissionConfigLoader} 分层加载。 */
public final class PermissionConfig {
  private String mode = "default";

  public String getMode() {
    return mode;
  }

  public void setMode(String mode) {
    this.mode = mode == null || mode.isBlank() ? "default" : mode;
  }
}
