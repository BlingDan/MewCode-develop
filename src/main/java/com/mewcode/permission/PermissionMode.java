package com.mewcode.permission;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import java.util.Locale;
import java.util.Objects;

/** 四档整体权限模式。 */
public enum PermissionMode {
  DEFAULT("default"),
  ACCEPT_EDITS("acceptEdits"),
  PLAN("plan"),
  BYPASS_PERMISSIONS("bypassPermissions");

  private final String configValue;

  PermissionMode(String configValue) {
    this.configValue = configValue;
  }

  public String configValue() {
    return configValue;
  }

  public static PermissionMode parse(String value) {
    if (value == null || value.isBlank()) return DEFAULT;
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "default" -> DEFAULT;
      case "acceptedits", "accept_edits", "accept-edits" -> ACCEPT_EDITS;
      case "plan" -> PLAN;
      case "bypasspermissions", "bypass_permissions", "bypass-permissions" -> BYPASS_PERMISSIONS;
      default -> throw new IllegalArgumentException("unknown permission mode: " + value);
    };
  }

  /** 只计算普通操作的默认值；黑名单、路径沙箱和 OS 沙箱由闸门单独处理。 */
  public PermissionDecision defaultDecision(Tool tool) {
    Objects.requireNonNull(tool, "tool");
    if (tool.isReadOnly() && !tool.isDestructive()) return PermissionDecision.ALLOW;
    if (this == BYPASS_PERMISSIONS) return PermissionDecision.ALLOW;
    if (this == ACCEPT_EDITS && tool.category() == ToolCategory.FILE) {
      return PermissionDecision.ALLOW;
    }
    return PermissionDecision.ASK;
  }
}
