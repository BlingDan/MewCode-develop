package com.mewcode.permission;

/** 权限规则来源，声明顺序由 {@link PermissionRuleEngine} 负责。 */
public enum RuleSource {
  USER,
  PROJECT,
  LOCAL,
  SESSION
}
