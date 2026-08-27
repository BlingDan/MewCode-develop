package com.mewcode.permission;

/** 产生权限结果的原因，便于确认框和模型错误理解。 */
public enum PermissionReason {
  DANGEROUS_COMMAND,
  PATH_SANDBOX,
  BASH_SANDBOX_UNAVAILABLE,
  RULE_ALLOW,
  RULE_DENY,
  MODE,
  USER,
  CONFIGURATION
}
